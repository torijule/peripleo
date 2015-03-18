package index.objects

import com.spatial4j.core.distance.DistanceUtils
import com.vividsolutions.jts.geom.Coordinate
import index._
import index.annotations.AnnotationReader
import models.Page
import models.core.Datasets
import models.geo.BoundingBox
import org.apache.lucene.util.Version
import org.apache.lucene.index.{ Term, MultiReader }
import org.apache.lucene.facet.FacetsCollector
import org.apache.lucene.facet.taxonomy.FastTaxonomyFacetCounts
import org.apache.lucene.search._
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.spatial.query.{ SpatialArgs, SpatialOperation }
import org.apache.lucene.search.suggest.analyzing.AnalyzingSuggester
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.search.spell.LuceneDictionary
import play.api.db.slick._
import scala.collection.JavaConverters._
import play.api.Logger
import org.apache.lucene.search.suggest.analyzing.FreeTextSuggester
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.spell.SpellChecker
import org.apache.lucene.spatial.prefix.HeatmapFacetCounter
import com.spatial4j.core.context.SpatialContextFactory
import com.spatial4j.core.shape.impl.RectangleImpl
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy
import com.spatial4j.core.shape.Rectangle
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader

trait ObjectReader extends AnnotationReader {

  /** Search the index.
    *  
    * In principle, every parameter is optional. Recommended use of the constructor 
    * is via named arguments. Some combinations obviously don't make sense (e.g. a
    * bounding box restriction combined with a filter for places outside the bounding
    * box), so a minimum of common sense will be required when choosing your arguments.
    * 
    * @param query a query according to Lucene syntax
    * @param objectType restriction to specific object types (place, item, or dataset)
    * @param dataset restriction to a specific dataset
    * @param fromYear temporal restriction: start date
    * @param toYear temporal restriction: end date
    * @param places restriction to specific places (gazetteer URIs)
    * @param bbox restriction to a geographic bounding box
    * @param coord search around a specific center coordinate (requires 'radius' argument)
    * @param radius search radius around a 'coord' center
    * @param limit number of maximum hits to return
    * @param offset offset in the search result list 
    */ 
  def search(
      query: Option[String] = None,
      objectType: Option[IndexedObjectTypes.Value] = None,
      dataset: Option[String] = None,
      fromYear: Option[Int] = None,
      toYear: Option[Int] = None,      
      places: Seq[String] = Seq.empty[String], 
      bbox: Option[BoundingBox] = None,
      coord: Option[Coordinate] = None, 
      radius: Option[Double] = None,
      limit: Int = 20, 
      offset: Int = 0
    )(implicit s: Session): (Page[IndexedObject], FacetTree, Heatmap) = {
     
    // The part of the query that is common for search and heatmap calculation
    val baseQuery = prepareBaseQuery(objectType, dataset, fromYear, toYear, places, bbox, coord, radius)
    
    // Finalize search query and build heatmap filter
    val searchQuery = {
      val search = baseQuery.clone()
      if (query.isDefined) {
        val fields = Seq(IndexFields.TITLE, IndexFields.DESCRIPTION, IndexFields.PLACE_NAME, IndexFields.ITEM_FULLTEXT).toArray       
        search.add(new MultiFieldQueryParser(fields, analyzer).parse(query.get), BooleanClause.Occur.MUST)  
      }
      search
    }
    
    val heatmapFilter = {
      if (query.isDefined) {
        // We don't search the fulltext field! Fulltext heatmaps are handled by the annotation index
        val fields = Seq(IndexFields.TITLE, IndexFields.DESCRIPTION, IndexFields.PLACE_NAME).toArray       
        baseQuery.add(new MultiFieldQueryParser(fields, analyzer).parse(query.get), BooleanClause.Occur.MUST)  
      }
      new QueryWrapperFilter(baseQuery)
    }
    
    val placeSearcher = placeSearcherManager.acquire()
    val objectSearcher = objectSearcherManager.acquire()
    val searcher = new IndexSearcher(new MultiReader(objectSearcher.searcher.getIndexReader, placeSearcher.searcher.getIndexReader))
    
    try {   
      val (results, facets) = executeSearch(searchQuery, limit, offset, query, searcher, objectSearcher.taxonomyReader)
      val heatmap = 
        calculateItemHeatmap(heatmapFilter, searcher) +
        calculateAnnotationHeatmap(query, dataset, fromYear, toYear, places, bbox, coord, radius)
        
      (results, facets, heatmap)
    } finally {
      placeSearcherManager.release(placeSearcher)
      objectSearcherManager.release(objectSearcher)
    }
  }
  
  /** Constructs the query as far as it's common for search and heatmap computation **/
  private def prepareBaseQuery(
      objectType: Option[IndexedObjectTypes.Value] = None,
      dataset: Option[String] = None,
      fromYear: Option[Int] = None,
      toYear: Option[Int] = None,      
      places: Seq[String] = Seq.empty[String], 
      bbox: Option[BoundingBox] = None,
      coord: Option[Coordinate] = None, 
      radius: Option[Double] = None)(implicit s: Session): BooleanQuery = {
    
    val q = new BooleanQuery()
      
    // Object type filter
    if (objectType.isDefined)
      q.add(new TermQuery(new Term(IndexFields.OBJECT_TYPE, objectType.get.toString)), BooleanClause.Occur.MUST)
    
    // Dataset filter
    if (dataset.isDefined) {
      val datasetHierarchy = dataset.get +: Datasets.listSubsetsRecursive(dataset.get)
      if (datasetHierarchy.size == 1) {
        q.add(new TermQuery(new Term(IndexFields.ITEM_DATASET, dataset.get)), BooleanClause.Occur.MUST)        
      } else {
        val datasetQuery = new BooleanQuery()
        datasetHierarchy.foreach(id => {
          datasetQuery.add(new TermQuery(new Term(IndexFields.ITEM_DATASET, id)), BooleanClause.Occur.SHOULD)       
        })
        q.add(datasetQuery, BooleanClause.Occur.MUST)
      }
    }
      
    // Timespan filter
    if (fromYear.isDefined || toYear.isDefined) {
      val timeIntervalQuery = new BooleanQuery()
      
      if (fromYear.isDefined)
        timeIntervalQuery.add(NumericRangeQuery.newIntRange(IndexFields.DATE_TO, fromYear.get, null, true, true), BooleanClause.Occur.MUST)
        
      if (toYear.isDefined)
        timeIntervalQuery.add(NumericRangeQuery.newIntRange(IndexFields.DATE_FROM, null, toYear.get, true, true), BooleanClause.Occur.MUST)
        
      q.add(timeIntervalQuery, BooleanClause.Occur.MUST)
    }
    
    // Places filter
    places.foreach(uri =>
      q.add(new TermQuery(new Term(IndexFields.ITEM_PLACES, uri)), BooleanClause.Occur.MUST))
    
    // Spatial filter
    if (bbox.isDefined) {
      val rectangle = Index.spatialCtx.makeRectangle(bbox.get.minLon, bbox.get.maxLon, bbox.get.minLat, bbox.get.maxLat)
      q.add(Index.spatialStrategy.makeQuery(new SpatialArgs(SpatialOperation.IsWithin, rectangle)), BooleanClause.Occur.MUST)
    } else if (coord.isDefined) {
      // Warning - there appears to be a bug in Lucene spatial that flips coordinates!
      val circle = Index.spatialCtx.makeCircle(coord.get.y, coord.get.x, DistanceUtils.dist2Degrees(radius.getOrElse(10), DistanceUtils.EARTH_MEAN_RADIUS_KM))
      q.add(Index.spatialStrategy.makeQuery(new SpatialArgs(SpatialOperation.IsWithin, circle)), BooleanClause.Occur.MUST)        
    }
    
    q
  }
  
  private def executeSearch(query: Query, limit: Int, offset: Int, queryString: Option[String], 
      searcher: IndexSearcher, taxonomyReader: DirectoryTaxonomyReader): (Page[IndexedObject], FacetTree) = {
    
    val facetsCollector = new FacetsCollector() 
    val topDocsCollector = TopScoreDocCollector.create(offset + limit)
    searcher.search(query, MultiCollector.wrap(topDocsCollector, facetsCollector))
      
    val facetTree = new FacetTree(new FastTaxonomyFacetCounts(taxonomyReader, Index.facetsConfig, facetsCollector))      
    val total = topDocsCollector.getTotalHits
    val results = topDocsCollector.topDocs(offset, limit).scoreDocs.map(scoreDoc => new IndexedObject(searcher.doc(scoreDoc.doc)))
    (Page(results.toSeq, offset, limit, total, queryString), facetTree)
  }
  
  private def calculateItemHeatmap(filter: Filter, searcher: IndexSearcher): Heatmap = {
    val heatmap = HeatmapFacetCounter.calcFacets(Index.spatialStrategy, searcher.getTopReaderContext, filter, new RectangleImpl(-90, 90, -90, 90, null), 3, 18000)
      
    // Heatmap grid cells with non-zero count, in the form of a tuple (x, y, count)
    val nonEmptyCells = 
      Seq.range(0, heatmap.rows).flatMap(row => {
        Seq.range(0, heatmap.columns).map(column => (column, row, heatmap.getCount(column, row)))
      }).filter(_._3 > 0)

    // Convert non-zero grid cells to map points
    val region = heatmap.region
    val (minX, minY) = (region.getMinX, region.getMinY)
    val cellWidth = region.getWidth / heatmap.columns
    val cellHeight = region.getHeight / heatmap.rows
      
    Heatmap(nonEmptyCells.map { case (x, y, count) =>
      val lon = DistanceUtils.normLonDEG(minX + x * cellWidth + cellWidth / 2)
      val lat = DistanceUtils.normLatDEG(minY + y * cellHeight + cellHeight / 2)
      (lon, lat, count)
    })
  }

}
