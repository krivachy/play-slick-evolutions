import java.io.File

import com.typesafe.config.ConfigFactory
import play.api.db.DBApi
import play.api.db.evolutions.Evolutions
import play.api.{Application, _}
import slick.codegen.SourceCodeGenerator
import slick.driver.H2Driver

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


/**
 *  This code generator runs Play Framework Evolutions against an in-memory database
 *  and then generates code from this database using the default Slick code generator.
 *
 *  Parameters: Output directory for the generated code.
 *
 *  Other parameters are taken from this modules conf/application.conf
 */
object PlaySlickCodeGenerator {

  private var playInstance = Option.empty[Application]

  private val ExcludedTables = Seq("play_evolutions")

  def main(args: Array[String]) = {
    try
    {
      run(outputDir = args(0))
    }
    catch {
      case ex: Throwable =>
        println("Could not generate code: " + ex.getMessage)
        ex.printStackTrace()
    }
    finally {
      playInstance.foreach(Play.stop)
    }
  }

  private def run(outputDir: String) = {

    // Use FakeApplication to spin up Play Instance
    implicit val app: Application = play.api.test.FakeApplication(
      path = new File("dbgen").getCanonicalFile,
      classloader = Thread.currentThread().getContextClassLoader
    )
    playInstance = Some(app)

    Play.start(app)

    val config = app.configuration.underlying.withFallback(ConfigFactory.defaultReference())
    val configuration = Configuration(config)
    // read database configuration
    val databaseNames = configuration.getConfig("slick.dbs").toSeq.flatMap(_.subKeys)
    val databaseName = databaseNames.headOption
      .getOrElse(throw new IllegalArgumentException("No database name found in configuration"))
    val outputPackage = configuration.getString(s"db.$databaseName.outputPackage")
      .getOrElse(throw new IllegalArgumentException("No outputPackage found in configuration"))
    val outputProfile = configuration.getString(s"db.$databaseName.outputProfile")
      .getOrElse(throw new IllegalArgumentException("No outputProfile found in configuration"))

    val dBApi = app.injector.instanceOf[DBApi]
    val db = dBApi.database(databaseName)
    val slickDb = slick.jdbc.JdbcBackend.Database.forDataSource(db.dataSource)

    Evolutions.applyFor(databaseName)

    // get list of tables for which code will be generated
    // also, we exclude the play evolutions table
    val listOfTablesQuery = H2Driver.defaultTables.map(_.filterNot(t => ExcludedTables.contains(t.name.name.toLowerCase)))
    val createDatabaseDbAction = H2Driver.createModel(Some(listOfTablesQuery))
    val model = Await.result(slickDb.run(createDatabaseDbAction), 20.seconds)


    // generate slick db code
    val codegen = new SourceCodeGenerator(model) {

      // add some custom import
      //override def code = "import foo.{MyCustomType,MyCustomTypeMapper}" + "\n" + super.code

      // override table generator
      override def Table = model =>  new Table(model.copy(name = model.name.copy(schema = None))) {

      }
    }

    codegen.writeToFile(
      profile = outputProfile,
      folder = outputDir,
      pkg = outputPackage,
      container = "Tables",
      fileName = "Tables.scala")

    playInstance.foreach(Play.stop)
  }

}