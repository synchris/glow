package com.databricks.vcf

import java.io.ByteArrayOutputStream

import com.databricks.hls.common.logging._
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.sources.DataSourceRegister
import org.seqdoop.hadoop_bam.util.DatabricksBGZFOutputStream
import com.databricks.hls.sql.util.SerializableConfiguration
import com.databricks.sql.BigFileDatasource

class BigVCFDatasource extends BigFileDatasource with DataSourceRegister {

  override def shortName(): String = "com.databricks.bigvcf"

  override def serializeDataFrame(options: Map[String, String], data: DataFrame): RDD[Array[Byte]] =
    BigVCFDatasource.serializeDataFrame(options, data)
}

object BigVCFDatasource extends HlsUsageLogging {
  def serializeDataFrame(options: Map[String, String], data: DataFrame): RDD[Array[Byte]] = {

    val dSchema = data.schema
    val rdd = data.queryExecution.toRdd
    val nParts = rdd.getNumPartitions
    val serializableConf = new SerializableConfiguration(
      VCFFileFormat.hadoopConfWithBGZ(data.sparkSession.sparkContext.hadoopConfiguration)
    )
    val writerFactory = new VCFOutputWriterFactory(options)
    rdd.mapPartitionsWithIndex {
      case (idx, it) =>
        val conf = serializableConf.value
        val codec = new CompressionCodecFactory(conf)
        val baos = new ByteArrayOutputStream()
        val outputStream = Option(codec.getCodec(new Path(BigFileDatasource.checkPath(options))))
          .map(_.createOutputStream(baos))
          .getOrElse(baos)

        // Write an empty GZIP block iff this is the last partition
        DatabricksBGZFOutputStream.setWriteEmptyBlockOnClose(outputStream, idx == nParts - 1)

        // Write the header if this is the first partition
        val writer = new VCFFileWriter(options, dSchema, conf, outputStream, idx == 0)

        it.foreach { row =>
          writer.write(row)
        }

        writer.close()

        // record bigVcfWrite event in the log
        recordHlsUsage(
          HlsMetricDefinitions.EVENT_HLS_USAGE,
          Map(
            HlsTagDefinitions.TAG_EVENT_TYPE -> HlsTagValues.EVENT_BIGVCF_WRITE
          )
        )

        Iterator(baos.toByteArray)
    }
  }
}
