package index

import index.objects._
import index.places._
import java.io.File
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.facet.FacetsConfig
import org.apache.lucene.facet.taxonomy.{ TaxonomyWriter, SearcherTaxonomyManager }
import org.apache.lucene.facet.taxonomy.directory.{ DirectoryTaxonomyReader, DirectoryTaxonomyWriter }
import org.apache.lucene.index.{ DirectoryReader, IndexWriter, IndexWriterConfig, MultiReader }
import org.apache.lucene.search.{ IndexSearcher, SearcherFactory }
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.Version
import org.apache.lucene.search.spell.{ SpellChecker, LuceneDictionary }
import play.api.Logger

private[index] class IndexBase(placeIndexDir: File, objectIndexDir: File, taxonomyDir: File, spellcheckDir: File) {
  
  private val placeIndex = FSDirectory.open(placeIndexDir)
  
  private val objectIndex = FSDirectory.open(objectIndexDir)
  
  private val taxonomyIndex = FSDirectory.open(taxonomyDir)
  
  private val spellcheckIndex = FSDirectory.open(spellcheckDir)
  
  private val spellchecker = new SpellChecker(spellcheckIndex)
  
  protected var placeIndexReader = DirectoryReader.open(placeIndex)
  
  protected val analyzer = new StandardAnalyzer(Version.LUCENE_4_9)

  protected val facetsConfig = new FacetsConfig()
  facetsConfig.setHierarchical(IndexFields.OBJECT_TYPE, false)

  protected val searcherTaxonomyMgr = new SearcherTaxonomyManager(objectIndex, taxonomyIndex, new SearcherFactory())
  
  protected def newObjectWriter(): (IndexWriter, TaxonomyWriter) =
    (new IndexWriter(objectIndex, new IndexWriterConfig(Version.LUCENE_4_9, analyzer)), new DirectoryTaxonomyWriter(taxonomyIndex))
    
  protected def newPlaceWriter(): IndexWriter = 
    new IndexWriter(placeIndex, new IndexWriterConfig(Version.LUCENE_4_9, analyzer))
  
  protected def newPlaceSearcher(): IndexSearcher = 
    new IndexSearcher(placeIndexReader)

  def numObjects: Int = {
    val searcherAndTaxonomy = searcherTaxonomyMgr.acquire()
    val numObjects = searcherAndTaxonomy.searcher.getIndexReader().numDocs()
    searcherTaxonomyMgr.release(searcherAndTaxonomy)
    numObjects
  }
  
  def numPlaceNetworks: Int =
    placeIndexReader.numDocs()
  
  def refresh() = {
    Logger.info("Refreshing index readers")
    searcherTaxonomyMgr.maybeRefresh()
    
    placeIndexReader.close()
    placeIndexReader = DirectoryReader.open(placeIndex)
  }
  
  def close() = {
    analyzer.close()
    searcherTaxonomyMgr.close()
    
    placeIndex.close()
    placeIndexReader.close()
    
    objectIndex.close()
    taxonomyIndex.close()
    
    spellchecker.close()
    spellcheckIndex.close()
  }
  
  def buildSpellchecker() = {
	// Logger.info("Building spellcheck index")
    // spellchecker.indexDictionary(new LuceneDictionary(placeIndexReader, IndexFields.PLACE_NAME), new IndexWriterConfig(Version.LUCENE_4_9, analyzer), true);
    // val test = spellchecker.suggestSimilar("vindobuna", 5)
    // test.foreach(s => Logger.info(s))
  }
      
}

class Index private(placeIndexDir: File, objectIndexDir: File, taxonomyDir: File, spellcheckDir: File)
  extends IndexBase(placeIndexDir, objectIndexDir, taxonomyDir, spellcheckDir)
    with ObjectReader
    with ObjectWriter
    with PlaceReader
    with PlaceWriter
  
object Index {
  
  def open(indexDir: String): Index = {
    val baseDir = new File(indexDir)
      
    val placeIndexDir = createIfNotExists(new File(baseDir, "gazetteer"))
    val objectIndexDir = createIfNotExists(new File(baseDir, "objects"))
    
    val taxonomyDirectory = new File(baseDir, "taxonomy")
    if (!taxonomyDirectory.exists) {
      taxonomyDirectory.mkdirs()
      val taxonomyInitializer = new DirectoryTaxonomyWriter(FSDirectory.open(taxonomyDirectory))
      taxonomyInitializer.close()
    }
    
    val spellcheckIndexDir = createIfNotExists(new File(baseDir, "spellcheck"))
    
    new Index(placeIndexDir, objectIndexDir, taxonomyDirectory, spellcheckIndexDir)
  }
  
  private def createIfNotExists(dir: File): File = {
    if (!dir.exists) {
      dir.mkdirs()  
      val initConfig = new IndexWriterConfig(Version.LUCENE_4_9, new StandardAnalyzer(Version.LUCENE_4_9))
      val initializer = new IndexWriter(FSDirectory.open(dir), initConfig)
      initializer.close()      
    }
    
    dir  
  }
  
  def normalizeURI(uri: String) = {
    val noFragment = if (uri.indexOf('#') > -1) uri.substring(0, uri.indexOf('#')) else uri
    if (noFragment.endsWith("/"))
      noFragment.substring(0, noFragment.size - 1)
    else 
      noFragment
  }
  
}
