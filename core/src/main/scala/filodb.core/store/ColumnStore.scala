package filodb.core.store

import java.net.InetAddress
import java.util.UUID

import filodb.core.Types._
import filodb.core.metadata._
import filodb.core.query.Dataflow.RowReaderFactory
import filodb.core.query.{ScanSplit, Dataflow, ScanInfo, SegmentScan}
import filodb.core.reprojector.Reprojector.SegmentFlush

import scala.concurrent.Future

trait ChunkStore {
  protected def appendChunk(projection: Projection,
                            partition: Any,
                            segment: Any,
                            chunk: ChunkWithMeta): Future[Boolean]

  protected def getKeySets(projection: Projection,
                           partition: Any,
                           segment: Any,
                           columns: Seq[ColumnId],
                           chunkIds: Seq[ChunkId]): Future[Seq[(ChunkId, Seq[_])]]


  protected def getChunks(scanInfo: ScanInfo)
  : Future[Seq[((Any, Any), Seq[ChunkWithMeta])]]

}

trait SummaryStore {
  type SegmentVersion = UUID

  /**
   * Atomically compare and swap the new SegmentSummary for this SegmentID
   */
  protected def compareAndSwapSummary(projection: Projection,
                                      partition: Any,
                                      segment: Any,
                                      oldVersion: Option[SegmentVersion],
                                      segmentVersion: SegmentVersion,
                                      segmentSummary: SegmentSummary): Future[Boolean]

  protected def readSegmentSummary(projection: Projection,
                                   partition: Any,
                                   segment: Any)
  : Future[Option[(SegmentVersion, SegmentSummary)]]

  protected def newVersion: SegmentVersion

}

trait QueryApi {

  def getScanSplits(splitCount: Int,
                    splitSize: Long,
                    projection: Projection,
                    columns:Seq[ColumnId],
                    partition: Option[Any] = None,
                    segmentRange: Option[KeyRange[_]] = None): Future[Seq[ScanSplit]]

}

trait ColumnStore {
  self: ChunkStore with SummaryStore with QueryApi =>

  import scala.concurrent.ExecutionContext.Implicits.global


  def readSegments(scanInfo: ScanInfo)(implicit rowReaderFactory:RowReaderFactory): Future[Seq[Dataflow]] =
    for {
      segmentData <- getChunks(scanInfo)
      mapping = segmentData.map { case ((partitionKey, segmentId), data) =>
        new SegmentScan(
          DefaultSegment(scanInfo.projection, partitionKey, segmentId, data),
          scanInfo.columns)
      }
    } yield mapping

  /**
   * There is a particular thorny edge case where the summary is stored but the chunk is not.
   */
  def flushToSegment(segmentFlush: SegmentFlush): Future[Boolean] = {
    val projection: Projection =segmentFlush.projection
    val partition: Any = segmentFlush.partition
    val segment: Any = segmentFlush.segment
    for {
    // first get version and summary for this segment
      (oldVersion, segmentSummary) <- getVersionAndSummaryWithDefaults(projection, partition, segment)
      // make a new chunk using this version of summary from the flush
      newChunk <- newChunkFromSummary(projection, partition, segment, segmentFlush, segmentSummary)
      // now make a new summary with keys from the new flush and the new chunk id
      newSummary = segmentSummary.withKeys(newChunk.chunkId, segmentFlush.keys)
      // Compare and swap the new summary and chunk if the version stayed the same
      result <- compareAndSwapSummaryAndChunk(projection, partition, segment,
        oldVersion, newVersion, newChunk, newSummary)

    } yield result
  }

  protected[store] def compareAndSwapSummaryAndChunk(projection: Projection,
                                                     partition: Any,
                                                     segment: Any,
                                                     oldVersion: Option[SegmentVersion],
                                                     newVersion: SegmentVersion,
                                                     newChunk: ChunkWithMeta,
                                                     newSummary: SegmentSummary): Future[Boolean] = {
    for {
    // Compare and swap the new summary if the version stayed the same
      swapResult <- compareAndSwapSummary(projection, partition, segment, oldVersion, newVersion, newSummary)
      // hoping that chunk will be appended always. If this fails, the db is corrupted!!
      result <- if (swapResult) appendChunk(projection, partition, segment, newChunk) else Future(false)
    } yield result
  }

  private[store] def getVersionAndSummaryWithDefaults(projection: Projection,
                                                      partition: Any,
                                                      segment: Any) = {
    for {
      versionAndSummary <- readSegmentSummary(projection, partition, segment)
      (oldVersion, segmentSummary) = versionAndSummary match {
        case Some(vAndS) => (Some(vAndS._1), vAndS._2)
        case None => (None, DefaultSegmentSummary(projection.keyType, None))
      }
    } yield (oldVersion, segmentSummary)
  }

  /**
   * For testing only
   */
  private[store] def newChunkFromSummary(projection: Projection,
                                         partition: Any,
                                         segment: Any,
                                         segmentFlush: SegmentFlush,
                                         segmentSummary: SegmentSummary) = {
    val chunkId = segmentSummary.nextChunkId
    val possibleOverrides = segmentSummary.possibleOverrides(segmentFlush.keys)
    for {
      possiblyOverriddenChunks <- possibleOverrides.map { o =>
        getKeySets(projection, partition, segment, projection.keyColumns, o)
      }.getOrElse(Future(List.empty[(ChunkId, Seq[_])]))

      chunkOverrides = segmentSummary.actualOverrides(segmentFlush.keys, possiblyOverriddenChunks)

      newChunk = new DefaultChunk(chunkId,
        segmentFlush.keys,
        projection.columnNames,
        segmentFlush.columnVectors,
        segmentFlush.keys.length,
        if (chunkOverrides.isEmpty) None else Some(chunkOverrides))

    } yield newChunk
  }


}
