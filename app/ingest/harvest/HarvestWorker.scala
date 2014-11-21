package ingest.harvest

import java.net.URL
import java.io.File
import models.Associations
import models.core._
import global.Global
import play.api.Play.current
import play.api.db.slick._
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import sys.process._
import scala.io.Source
import ingest.CSVImporter
import ingest.PelagiosOAImporter
import java.util.UUID
import org.apache.commons.codec.digest.DigestUtils
import java.io.FileInputStream
import ingest.VoIDImporter
import org.pelagios.api.dataset.{ Dataset => VoIDDataset }
import java.security.MessageDigest
import java.math.BigInteger

class HarvestWorker {
  
  private val TMP_DIR = System.getProperty("java.io.tmpdir")
  
  private val UTF8 = "UTF-8"
    
  private val CSV = "csv"
  
  private val MD5 = "MD5"
    
  private val MAX_RETRIES = 5
  
  /** Helper to download a file from a URL **/
  private def downloadFile(url: String, filename: String, failedAttempts: Int = 0): Option[TemporaryFile] = {
    try {
      Logger.info("Downloading " + url)
      val tempFile = new TemporaryFile(new File(TMP_DIR, filename))
	  new URL(url) #> tempFile.file !!
	  
	  Logger.info("Download complete for " + url)
	  Some(tempFile)
    } catch {
      case t: Throwable => {
        if (failedAttempts < MAX_RETRIES) {
          Logger.info("Download failed - retrying " + url)
          downloadFile(url, filename, failedAttempts + 1)
        } else {
          Logger.info("Download failed " + failedAttempts + " - giving up")
          None
        }
      }
    }    
  }
  
  /** Helper to compute the hash of a file **/
  private def computeHash(file: File): String = computeHash(Seq(file))
  
  /** Helper to compute a hash for multiple files **/
  private def computeHash(files: Seq[File]): String = {
    val md = MessageDigest.getInstance(MD5)
    files.foreach(file => {
      val is = new FileInputStream(file);
      Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).grouped(1024).foreach(bytes => {
        md.update(bytes.toArray, 0, bytes.size)
      })
    })
    val mdBytes = md.digest()
    new BigInteger(1, mdBytes).toString(16)
  }
  
  /** Helper to get datadump URLs for a dataset and all its subsets **/
  private def getDataDumpURLs(datasets: Seq[VoIDDataset]): Seq[(String, VoIDDataset)] = {
    datasets.flatMap(dataset => {
      if (dataset.subsets.isEmpty)
        dataset.datadumps.map(uri => (uri, dataset))
      else
        dataset.datadumps.map(uri => (uri, dataset)) ++ getDataDumpURLs(dataset.subsets)
    })
  }
  
  /** Helper to drop a dataset and all its dependencies from DB and index 
    *
    * TODO there is now code duplication with the DatasetAdminController - resolve!
    */
  private def dropDatasetCascaded(id: String)(implicit s: Session) = {
    val subsetsRecursive = id +: Datasets.listSubsetsRecursive(id)

    // Purge from database
    Annotations.deleteForDatasets(subsetsRecursive)
    Associations.deleteForDatasets(subsetsRecursive)
    Images.deleteForDatasets(subsetsRecursive)
    AnnotatedThings.deleteForDatasets(subsetsRecursive)
    Datasets.delete(subsetsRecursive)
    
    // Purge from index
    Global.index.dropDatasets(subsetsRecursive)
    Global.index.refresh()    
  }
  
  private def importData(dataset: Dataset, dumpfiles: Seq[TemporaryFile])(implicit s: Session) = {
    dumpfiles.foreach(dump => {
	  Logger.info("Importing " + dump.file.getName)
	  if (dump.file.getName.endsWith(CSV))
        CSVImporter.importRecogitoCSV(Source.fromFile(dump.file, UTF8), dataset)
      else
        PelagiosOAImporter.importPelagiosAnnotations(dump, dump.file.getName, dataset)
           
      dump.finalize()
      Logger.info(dump.file.getName + " - import complete.")
	})    
  }
      
  /** (Re-)Harvest a dataset from a VoID URL **/
  def fullHarvest(voidURL: String, previous: Seq[Dataset]) = {	  
    Logger.info("Downloading VoID from " + voidURL)
    val startTime = System.currentTimeMillis
   
    // Assign a random (but unique) name, and keep the extension from the original file
    val voidFilename = "void_" + UUID.randomUUID.toString + voidURL.substring(voidURL.lastIndexOf("."))
    val voidTempFile = downloadFile(voidURL, voidFilename)
    
    if (voidTempFile.isDefined) {	
	  val voidHash = computeHash(voidTempFile.get.file)
	
	  val datasets = VoIDImporter.readVoID(voidTempFile.get)
	  voidTempFile.get.finalize()
	  
	  val dataDumps = getDataDumpURLs(datasets).par.map { case (url, dataset) => {
	    val dumpFilename = "data_" + UUID.randomUUID.toString + url.substring(url.lastIndexOf("."))
	    (url, downloadFile(url, dumpFilename))
	  }}.seq
	  
	  if (dataDumps.filter(_._1.isEmpty).size == 0) {
	    
	    // TODO compare hashes and only ingest on change
	    
	    DB.withSession { implicit session: Session =>	    
	      previous.foreach(dataset => dropDatasetCascaded(dataset.id))
	      
	      val dumpfileMap = dataDumps.toMap.mapValues(_.get)	      
	      val importedDatasetsWithDumpfiles = VoIDImporter.importVoID(datasets, Some(voidURL))
	      importedDatasetsWithDumpfiles.foreach { case (dataset, uris) => {
	        importData(dataset, uris.map(uri => dumpfileMap.get(uri).get))
	      }}
	    }
	  } else {
	    Logger.warn("Download failure - aborting " + voidURL)
	  }
    }
  }
	
  /*
  def harvest(datasetId: String) = {
    DB.withSession { implicit session: Session =>
      val d = Datasets.findByIdWithDumpfiles(datasetId)
      if (d.isEmpty) {
	    // TODO error notification
      } else {
	    val (dataset, dumpfileURLs) = d.get
 	    val dumpfiles = dumpfileURLs.par.map(d => { 
	      val filename = d.uri.substring(d.uri.lastIndexOf("/") + 1)
	      Logger.info("Downloading file " + filename + " from " + d.uri)
	      val tempFile = new TemporaryFile(new File(TMP_DIR, filename))
	      new URL(d.uri) #> tempFile.file !!
	      
	      Logger.info(filename + " - download complete.")
	      tempFile
	    }).seq
	    Logger.info("All downloads complete.")
	    
	    dumpfiles.foreach(file => {
	      Logger.info("Importing " + file.file.getName)
	      if (file.file.getName.endsWith(CSV))
            CSVImporter.importRecogitoCSV(Source.fromFile(file.file, UTF8), dataset)
          else
            PelagiosOAImporter.importPelagiosAnnotations(file, file.file.getName, dataset)
           
          file.finalize()
          Logger.info(file.file.getName + " - import complete.")
	    })
	    Logger.info("All downloads imported.")
     }
    }
  }
  */
	
}
