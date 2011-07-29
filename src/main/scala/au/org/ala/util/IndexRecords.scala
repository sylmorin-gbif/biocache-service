
package au.org.ala.util
import java.util.ArrayList
import org.slf4j.LoggerFactory
import au.org.ala.biocache._

/**
 * Index the Cassandra Records to conform to the fields
 * as defined in the schema.xml file.
 *
 * @author Natasha Carter
 */
object IndexRecords {

  val logger = LoggerFactory.getLogger("IndexRecords")
  val indexer = Config.getInstance(classOf[IndexDAO]).asInstanceOf[IndexDAO]
  val occurrenceDAO = Config.getInstance(classOf[OccurrenceDAO]).asInstanceOf[OccurrenceDAO]
  val persistenceManager = Config.getInstance(classOf[PersistenceManager]).asInstanceOf[PersistenceManager]

  def main(args: Array[String]): Unit = {
    
    var startUuid:Option[String] = None
    
    var dataResource:Option[String] = None
    var empty:Boolean =false
    val parser = new OptionParser("index records options") {
        opt("empty", "empty the index first", {empty=true})
        opt("s", "start","The record to start with", {v:String => startUuid = Some(v)})
        opt("dr", "resource", "The data resource to process", {v:String =>dataResource = Some(v)})
    }
    
    if(parser.parse(args)){
        //delete the content of the index
        if(empty){
           logger.info("Emptying index")
           indexer.emptyIndex
        }
        
        index(startUuid, dataResource, false, false)
     }
  }
  
  def index(startUuid:Option[String], dataResource:Option[String], optimise:Boolean = false, shutdown:Boolean = false)={
      
        val startKey = {
            if(startUuid.isEmpty && !dataResource.isEmpty) {
            	dataResource.get +"|"
            } else {
                startUuid.get
            }
        }
        
        val endKey = if(dataResource.isEmpty) "" else dataResource.get +"|~"
        logger.info("Starting to index " + startKey + " until " + endKey)
        indexRange(startKey, endKey)
        //index any remaining items before exiting
        indexer.finaliseIndex(optimise, shutdown)
      
  }
  
  
  def indexRange(startUuid:String, endUuid:String)={
    var counter = 0
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var items = new ArrayList[OccurrenceIndex]()
    persistenceManager.pageOverAll("occ", (guid, map)=> {
        counter += 1
        indexer.indexFromMap(guid, map)
        if (counter % 1000 == 0) {
          finishTime = System.currentTimeMillis
          logger.info(counter + " >> Last key : " + guid + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
          startTime = System.currentTimeMillis
        }
        true
    }, startUuid, endUuid)

    finishTime = System.currentTimeMillis
    logger.info("Total indexing time " + ((finishTime-start).toFloat)/1000f + " seconds")
  }

  def processFullRecords(){

    var counter = 0
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var items = new ArrayList[OccurrenceIndex]()

     //page over all records and process
    occurrenceDAO.pageOverAllVersions(versions => {
      counter += 1
      if (!versions.isEmpty) {
    	val v = versions.get
    	
    	val raw = v(0)
    	val processed = v(1)
    	
    	items.add(indexer.getOccIndexModel(raw,processed).get);
        //debug counter
        if (counter % 1000 == 0) {
          //add the items to the configured indexer
          indexer.index(items);
          items.removeAll(items);
          finishTime = System.currentTimeMillis
          logger.info(counter + " >> Last key : " + v(0).uuid + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
          startTime = System.currentTimeMillis
        }
      }
      true
    })
  }
}