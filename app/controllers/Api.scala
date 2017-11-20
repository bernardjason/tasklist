package controllers

import java.sql.Timestamp

import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success

import javax.inject.Inject
import models.TimeEntry
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.db.slick.DatabaseConfigProvider
import play.api.db.slick.HasDatabaseConfig
import play.api.libs.json.Json
import play.api.mvc.AbstractController
import play.api.mvc.ControllerComponents
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import tables.TimeEntryTable

class Api @Inject() (implicit ec: ExecutionContext ,components: ControllerComponents,cache: SyncCacheApi, 
                      protected val dbConfigProvider: DatabaseConfigProvider, securedAction: SecuredAction) 
  extends AbstractController(components)
  with TimeEntryTable with HasDatabaseConfig[JdbcProfile] {

  val logger: Logger = Logger(this.getClass())
  
  val dbConfig: DatabaseConfig[JdbcProfile] = dbConfigProvider.get[JdbcProfile]

  import dbConfig.profile.api._

  val timeentries = TableQuery[TimeEntries]

  def getTimeEntries(week: Option[String]) = securedAction.async { implicit request =>

    logger.info(s"WEEK IS ${week}")
    val userid = request.user.id
    logger.info(s"get time entries ${userid}")

    val mytasks = if (week.isEmpty) {
      for {
        timeentry <- timeentries.sortBy { x => x.when.desc } if timeentry.user_id === ( request.user.id)
      } yield (timeentry)
    } else {
      val start = new Timestamp(week.get.toLong)
      val end = new Timestamp(week.get.toLong + (60 * 60 * 24 * 7 * 1000))

      logger.info(s"Start ${start} end ${end}")

      for {
        timeentry <- timeentries.withFilter(w => w.when >= start && w.when < end).
          sortBy { x => x.when.desc } if timeentry.user_id === (request.user.id)
      } yield (timeentry)
    }

    db.run(mytasks.result).map { res =>
      {
        Ok(Json.toJson(res))
      }
    }
  }
  def postTimeEntry = securedAction.async(parse.json) { implicit request =>

    val timeEntry = request.body.as[TimeEntry]

    val t = TimeEntry(0, request.user.id, request.user.nickname,
      //new java.sql.Timestamp(Calendar.getInstance().getTime().getTime()),
      timeEntry.when,
      timeEntry.task_id, timeEntry.task, timeEntry.effort)

    db.run((timeentries += t).asTry).map(res =>
      res match {
        case Success(res) => Ok(Json.toJson(t))
        case Failure(e) => {
          logger.error(s"Problem on insert, ${e.getMessage}")
          InternalServerError(s"Problem on insert, ${e.getMessage}")
        }
      })
  }

  def deleteTimeEntry(id: Long) = securedAction.async { implicit request =>

    val toDelete = for {
      timeentry <- timeentries.filter { t => t.id === id }
    } yield (timeentry)
    db.run(toDelete.delete).map(res =>
      {
        Ok(Json.toJson(res))
      })

  }

}