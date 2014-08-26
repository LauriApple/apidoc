package controllers

import com.gilt.apidoc.models.Visibility
import lib.Validation
import core.{ ServiceDescription, ServiceDescriptionValidator }
import db.{ Organization, OrganizationDao, ServiceDao, User, Version, VersionDao, VersionForm }
import play.api.mvc._
import play.api.libs.json._

object Versions extends Controller {

  def getByOrgKeyAndServiceKey(orgKey: String, serviceKey: String, limit: Int = 25, offset: Int = 0) = Authenticated { request =>
    val versions = OrganizationDao.findByUserAndKey(request.user, orgKey).flatMap { org =>
      ServiceDao.findByOrganizationAndKey(org, serviceKey).map { service =>
        VersionDao.findAll(service_guid = Some(service.guid),
                           limit = limit,
                           offset = offset)
      }
    }.getOrElse(Seq.empty)
    Ok(Json.toJson(versions))
  }

  def getByOrgKeyAndServiceKeyAndVersion(orgKey: String, serviceKey: String, version: String) = Authenticated { request =>
    VersionDao.findVersion(request.user, orgKey, serviceKey, version) match {
      case None => NotFound
      case Some(v: Version) => Ok(Json.toJson(v))
    }
  }

  def putByOrgKeyAndServiceKeyAndVersion(
    orgKey: String,
    serviceKey: String,
    version: String
  ) = Authenticated(parse.json) { request =>
    // val visibility = visibilityKey.map(Visibility(_)).getOrElse(Visibility.Organization)
    val visibility = Visibility.Organization

    OrganizationDao.findByUserAndKey(request.user, orgKey) match {
      case None => {
        Conflict(Json.toJson(Validation.error(s"Organization[$orgKey] does not exist or you are not authorized to access it")))
      }

      case Some(org: Organization) => {

        request.body.validate[VersionForm] match {
          case e: JsError => {
            Conflict(Json.toJson(Validation.invalidJson(e)))
          }
          case s: JsSuccess[VersionForm] => {
            val form = s.get
            val validator = ServiceDescriptionValidator(form.json)

            if (validator.isValid) {
              val service = ServiceDao.findByOrganizationAndKey(org, serviceKey).getOrElse {
                ServiceDao.create(request.user, org, validator.serviceDescription.get.name, visibility, Some(serviceKey))
              }

              val resultingVersion = VersionDao.findByServiceAndVersion(service, version) match {
                case None => VersionDao.create(request.user, service, version, form.json)
                case Some(existing: Version) => VersionDao.replace(request.user, existing, service, form.json)
              }

              Ok(Json.toJson(resultingVersion))

            } else {
              Conflict(Json.toJson(Validation.errors(validator.errors)))
            }
          }
        }
      }
    }
  }

  def deleteByOrgKeyAndServiceKeyAndVersion(orgKey: String, serviceKey: String, version: String) = Authenticated { request =>
    VersionDao.findVersion(request.user, orgKey, serviceKey, version).map { version =>
      VersionDao.softDelete(request.user, version)
    }
    NoContent
  }

}
