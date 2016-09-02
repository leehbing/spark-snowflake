/*
 * Copyright 2015-2016 Snowflake Computing
 * Copyright 2015 TouchType Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowflakedb.spark.snowflakedb

import java.net.URI

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import org.apache.hadoop.conf.Configuration
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SQLContext}
import org.slf4j.LoggerFactory

import com.snowflakedb.spark.snowflakedb.Parameters.MergedParameters

/**
 * Data Source API implementation for Amazon Snowflake database tables
 */
private[snowflakedb] case class SnowflakeRelation(
    jdbcWrapper: JDBCWrapper,
    s3ClientFactory: AWSCredentials => AmazonS3Client,
    params: MergedParameters,
    userSchema: Option[StructType])
    (@transient val sqlContext: SQLContext)
  extends BaseRelation
  with PrunedFilteredScan
  with InsertableRelation {

  private val log = LoggerFactory.getLogger(getClass)

  if (sqlContext != null) {
    Utils.assertThatFileSystemIsNotS3BlockFileSystem(
      new URI(params.rootTempDir), sqlContext.sparkContext.hadoopConfiguration)
  }

  override lazy val schema: StructType = {
    userSchema.getOrElse {
      val tableNameOrSubquery =
        params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
      val conn = jdbcWrapper.getConnector(params)
      try {
        jdbcWrapper.resolveTable(conn, tableNameOrSubquery)
      } finally {
        conn.close()
      }
    }
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    val saveMode = if (overwrite) {
      SaveMode.Overwrite
    } else {
      SaveMode.Append
    }
    val writer = new SnowflakeWriter(jdbcWrapper, s3ClientFactory)
    writer.saveToSnowflake(sqlContext, data, saveMode, params)
  }

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    val creds =
      AWSCredentialsUtils.load(params.rootTempDir, sqlContext.sparkContext.hadoopConfiguration)
    if (params.checkBucketConfiguration) {
      Utils.checkThatBucketHasObjectLifecycleConfiguration(
          params.rootTempDir, s3ClientFactory(creds))
    }
    if (requiredColumns.isEmpty) {
      // In the special case where no columns were requested, issue a `count(*)` against Snowflake
      // rather than unloading data.
      val whereClause = FilterPushdown.buildWhereClause(schema, filters)
      val tableNameOrSubquery = params.query.map(q => s"($q)").orElse(params.table).get
      val countQuery = s"SELECT count(*) FROM $tableNameOrSubquery $whereClause"
      log.debug(Utils.sanitizeQueryText(countQuery))
      val conn = jdbcWrapper.getConnector(params)
      try {
        val results = jdbcWrapper.executeQueryInterruptibly(conn, countQuery)
        if (results.next()) {
          val numRows = results.getLong(1)
          val parallelism = sqlContext.getConf("spark.sql.shuffle.partitions", "200").toInt
          val emptyRow = Row.empty
          sqlContext.sparkContext.parallelize(1L to numRows, parallelism).map(_ => emptyRow)
        } else {
          throw new IllegalStateException("Could not read count from Snowflake")
        }
      } finally {
        conn.close()
      }
    } else {
      // Unload data from Snowflake into a temporary directory in S3:
      val tempDir = params.createPerQueryTempDir()
      val unloadSql = buildUnloadStmt(requiredColumns, filters, tempDir)
      val conn = jdbcWrapper.getConnector(params)
      var numRows = 0
      try {
        // Prologue
        val prologueSql = Utils.genPrologueSql(params)
        log.debug(Utils.sanitizeQueryText(prologueSql))
        jdbcWrapper.executeInterruptibly(conn, prologueSql)

        Utils.executePreActions(jdbcWrapper, conn, params)

        // Run the unload query
        log.debug(Utils.sanitizeQueryText(unloadSql))
        val res = jdbcWrapper.executeQueryInterruptibly(conn, unloadSql)

        // Verify it's the expected format
        val sch = res.getMetaData
        assert(sch.getColumnCount == 3)
        assert(sch.getColumnName(1) == "rows_unloaded")
        assert(sch.getColumnTypeName(1) == "NUMBER")
        // First record must be in
        val first = res.next()
        assert(first)
        numRows = res.getInt(1)
        // There can be no more records
        val second = res.next()
        assert(!second)

        Utils.executePostActions(jdbcWrapper, conn, params)

        // Epilogue - disabled for now, as we close the connection
//        log.debug(epilogueSql)
//        jdbcWrapper.executeInterruptibly(conn, epilogueSql)
      } finally {
        conn.close()
      }
      val prunedSchema = pruneSchema(schema, requiredColumns)
      if (numRows == 0) {
        // For no records, create an empty RDD
        sqlContext.sparkContext.emptyRDD[Row]
      } else {
        // Create a DataFrame to read the unloaded data:
        val rdd = sqlContext.sparkContext.newAPIHadoopFile(
          tempDir,
          classOf[SnowflakeInputFormat],
          classOf[java.lang.Long],
          classOf[Array[String]])
        rdd.values.mapPartitions { iter =>
          val converter: Array[String] => Row = Conversions.createRowConverter(prunedSchema)
          iter.map(converter)
        }
      }
    }
  }

  private def buildUnloadStmt(
      requiredColumns: Array[String],
      filters: Array[Filter],
      tempDir: String): String = {
    assert(!requiredColumns.isEmpty)
    // Always quote column names:
    val columnList = requiredColumns.map(col => s""""$col"""").mkString(", ")
    val whereClause = FilterPushdown.buildWhereClause(schema, filters)
    var credsString = AWSCredentialsUtils.getSnowflakeCredentialsString(sqlContext, params)
    val query = {
      val tableNameOrSubquery =
            params.query.map(q => s"($q)").orElse(params.table.map(_.toString)).get
      s"SELECT $columnList FROM $tableNameOrSubquery $whereClause"
    }
    val fixedUrl = Utils.fixS3Url(tempDir)

    // Save the last SELECT so it can be inspected
    Utils.setLastSelect(query);

    // Snowflake-todo Compression support
    s"""
       |COPY INTO '$fixedUrl'
       |FROM ($query)
       |$credsString
       |FILE_FORMAT = (
       |    TYPE=CSV
       |    COMPRESSION=none
       |    FIELD_DELIMITER='|'
       |    /*ESCAPE='\\\\'*/
       |    /*TIMESTAMP_FORMAT='YYYY-MM-DD HH24:MI:SS.FF3 TZHTZM'*/
       |    FIELD_OPTIONALLY_ENCLOSED_BY='"'
       |    NULL_IF= ()
       |  )
       |MAX_FILE_SIZE = ${params.s3maxfilesize}
       |""".stripMargin.trim
  }

  private def pruneSchema(schema: StructType, columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields.map(x => x.name -> x): _*)
    new StructType(columns.map(name => fieldMap(name)))
  }
}
