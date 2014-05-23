package controllers

import controllers.common.io.JSONWrites._
import models._
import play.api.db.slick._
import play.api.libs.json.{ Json, JsValue }
import global.Global

object DatasetController extends AbstractAPIController {
  
  def listAll(offset: Option[Int], limit: Option[Int], prettyPrint: Option[Boolean]) = DBAction { implicit session =>
    jsonOk(Json.toJson(Datasets.listAll(offset.getOrElse(0), limit.getOrElse(Global.DEFAULT_PAGE_SIZE))), prettyPrint)
  }
  
  def getDataset(id: String, prettyPrint: Option[Boolean]) = DBAction { implicit session =>
    val dataset = Datasets.findById(id)
    if (dataset.isDefined)
      jsonOk(Json.toJson(dataset.get), prettyPrint)
    else
      NotFound(Json.parse("{ \"message\": \"Not found\" }"))
  }
    
  def listAnnotatedThings(id: String, offset: Option[Int], limit: Option[Int], prettyPrint: Option[Boolean]) = DBAction { implicit session =>
    val dataset = Datasets.findById(id)
    if (dataset.isDefined)
      jsonOk(Json.toJson(AnnotatedThings.findByDataset(id, offset.getOrElse(0), limit.getOrElse(Global.DEFAULT_PAGE_SIZE))), prettyPrint)
    else
      NotFound(Json.parse("{ \"message\": \"Not found\" }"))
  }
  
  def listPlaces(id: String, offset: Option[Int], limit: Option[Int], prettyPrint: Option[Boolean]) = DBAction { implicit session =>
    val places = Places.findPlacesInDataset(id, offset.getOrElse(0), limit.getOrElse(Global.DEFAULT_PAGE_SIZE))
    jsonOk(Json.toJson(places), prettyPrint)
  } 
  
}