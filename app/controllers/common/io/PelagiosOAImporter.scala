package controllers.common.io

import global.Global
import java.util.{ Calendar, UUID }
import java.io.FileInputStream
import java.sql.Date
import models._
import play.api.Logger
import play.api.db.slick._
import play.api.libs.Files.TemporaryFile
import play.api.mvc.RequestHeader
import play.api.mvc.MultipartFormData.FilePart
import org.openrdf.rio.RDFFormat
import org.pelagios.Scalagios

object PelagiosOAImporter extends AbstractImporter {

  def importPelagiosAnnotations(file: FilePart[TemporaryFile], dataset: Dataset)(implicit s: Session, r: RequestHeader) = {
    Logger.info("Reading Pelagios annotations from RDF: " + file.filename) 
    val format = getFormat(file.filename)
    
    // If we don't have a base URI for the VoID file, we'll use our own namespace as fallback
    // Not 100% the Sesame parser actually makes use of it... but we're keeping things sane nonetheless
    val baseURI = controllers.routes.DatasetController.listAll().absoluteURL(false)(r)
    val annotatedThings = Scalagios.readAnnotations(new FileInputStream(file.ref.file), baseURI, format)
    Logger.info("Importing " + annotatedThings.size + " annotated things with " + annotatedThings.flatMap(_.annotations).size + " annotations")
    
    // Parse data
    val ingestBatch: Seq[(AnnotatedThing, Seq[Annotation])] = annotatedThings.toSeq.map(oaThing => { 
      val thingId = md5(oaThing.uri)
      val cal = Calendar.getInstance()
      
      val tempBoundsStart = oaThing.temporal.map(temporalPeriod => {
        cal.setTime(temporalPeriod.start)
        cal.get(Calendar.YEAR)
      })    
      
      val tempBoundsEnd = if (tempBoundsStart.isDefined) {
        val periodEnd = oaThing.temporal.flatMap(_.end) // .map(endDate => {
        if (periodEnd.isDefined) {
          cal.setTime(periodEnd.get)
          Some(cal.get(Calendar.YEAR))
        } else {
          tempBoundsStart // Repeat start date in case no end is defined
        }  
      } else {
        None
      }
      
      val thing = AnnotatedThing(thingId, dataset.id, oaThing.title, None, oaThing.homepage, tempBoundsStart, tempBoundsEnd)
      val annotations = oaThing.annotations.map(a => Annotation(UUID.randomUUID, dataset.id, thingId, new GazetteerURI(a.place.head)))     
      (thing, annotations)
    })
      
    // Insert data into DB and index
    val allThings = ingestBatch.map(_._1)
    val allAnnotations = ingestBatch.flatMap(_._2)
    AnnotatedThings.insertAll(allThings)
    Global.index.addAnnotatedThings(allThings)
    Annotations.insertAll(allAnnotations)
    
    // Update the parent dataset with new temporal bounds
    val datedThings = allThings.filter(_.temporalBoundsStart.isDefined)
    val tempBoundsStart = datedThings.map(_.temporalBoundsStart.get).min
    val tempBoundsEnd = datedThings.map(_.temporalBoundsEnd.get).max
    
    val updatedDataset = Dataset(dataset.id, dataset.title, dataset.publisher, dataset.license,
        dataset.created, new Date(System.currentTimeMillis), dataset.voidURI, dataset.description, 
        dataset.homepage, dataset.datadump)
        
    Datasets.update(updatedDataset)     
  }
  
}