package controllers

import com.gilt.apidoc.models.{OrganizationMetadata, Version}
import core.ServiceDescription
import db.{OrganizationDao, VersionDao}
import lib.Validation
import core.generator.Target

import play.api.mvc._
import play.api.libs.json._
import play.api.Play.current


object Code extends Controller {

  val apidocVersion = current.configuration.getString("apidoc.version").getOrElse {
    sys.error("apidoc.version is required")
  }

  case class Code(
    target: String,
    source: String
  )

  object Code {

    implicit val codeWrites = Json.writes[Code]

  }

  def getByOrgKeyAndServiceKeyAndVersionAndTarget(orgKey: String, serviceKey: String, version: String, targetName: String) = Authenticated { request =>
    OrganizationDao.findByUserAndKey(request.user, orgKey) match {
      case None => NotFound
      case Some(org) => {
        Target.findByKey(targetName) match {
          case None => {
            Conflict(Json.toJson(Validation.error(s"Invalid target[$targetName]. Must be one of: ${Target.Implemented.mkString(" ")}")))
          }
          case Some(target: Target) => {
            VersionDao.findVersion(request.user, orgKey, serviceKey, version) match {
              case None => Conflict(Json.toJson(Validation.error(s"Invalid service[$serviceKey] or version[$version]")))
              case Some(v: Version) => {
                val code = Code(
                  target = targetName,
                  source = Target.generate(target, apidocVersion, org.key, org.metadata, ServiceDescription(v.json), serviceKey, version)
                )
                Ok(Json.toJson(code))
              }
            }
          }
        }
      }
    }
  }

}
