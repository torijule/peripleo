package index.annotations

import models.core.Annotation
import com.vividsolutions.jts.geom.Geometry

trait AnnotationWriter extends AnnotationReader {

  def addAnnotations(annotations: Seq[(Annotation, Option[Int], Option[Int], Geometry, Option[String], Option[String])]) =
    annotations.foreach { case (annotation, tempBoundsStart, tempBoundsEnd, geometry, prefix, suffix) =>
      annotationWriter.addDocument(IndexedAnnotation.toDoc(annotation, tempBoundsStart, tempBoundsEnd, geometry, prefix, suffix)) }
  
}