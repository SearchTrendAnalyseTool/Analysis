package main

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.types.StructType

// TODO: find suitable abstraction for all spark jobs in order to avoid code duplication
object RedditSparkJob extends App {
  override def main(args: Array[String]): Unit = {

    val sparkCommons = SparkCommons
    val cl = ConfigLoader

    // mongodb connection
    val connectionUri =
      s"mongodb://${cl.username}:${cl.password}@${cl.host}:${cl.port}/?authMechanism=SCRAM-SHA-256&authSource=Projektstudium"

    // local mongodb docker container for testing purposes
    val localReplicaSet =
      "mongodb://mongo1:30001,mongo2:30002,mongo3:30003/?replicaSet=my-replica-set"

    // pretrained ML model
    val model: SentimentModel = new RedditSentimentModel()

    val schema = new StructType()
      .add("subreddit", "string")
      .add("url", "string")
      .add("date", "string")
      .add("selftext", "string")
      .add("title", "string")
      .add("comments", "string")

    // setup read stream
    val readQuery = sparkCommons.spark.readStream
      .format("mongodb")
      .schema(schema)
      .option("spark.mongodb.connection.uri", localReplicaSet)
      .option("spark.mongodb.database", "StreamTest")
      .option("spark.mongodb.collection", "reddit")
      .option("spark.mongodb.change.stream.publish.full.document.only", "true")
      .option("checkpointLocation", "../tmp/checkpint/main/read")
      .option("forceDeleteTempCheckpointLocation", "true")
      .load()

    // write stream does not support changing the data
    // thats why data is written in batch mode
    // when a single document comes in it is still being processed
    val writeQuery = readQuery.writeStream
      .foreachBatch((batchDf: DataFrame, batchId: Long) => {
        val analyzedDf = model
          .transformDataframe(batchDf)

        analyzedDf.write
          .format("mongodb")
          .mode("append")
          .option("spark.mongodb.connection.uri", localReplicaSet)
          .option("spark.mongodb.database", "StreamTest")
          .option("spark.mongodb.collection", "redditOut")
          .save()
      })
      .option("checkpointLocation", "../tmp/checkpoint/main/write")
      .option("forceDeleteTempCheckpointLocation", "true")
      .start()
      .awaitTermination()
  }
}
