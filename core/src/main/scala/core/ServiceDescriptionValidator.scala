package core

import java.util.UUID

import codegenerator.models.{Field, ServiceDescription}
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.{JsObject, Json, JsValue}
import com.fasterxml.jackson.core.{ JsonParseException, JsonProcessingException }
import com.fasterxml.jackson.databind.JsonMappingException

case class ServiceDescriptionValidator(apiJson: String) {

  private val RequiredFields = Seq("base_url", "name")

  private var parseError: Option[String] = None

  lazy val serviceDescription: Option[ServiceDescription] = {
    internalServiceDescription.map { ServiceDescriptionBuilder(_) }
  }

  def validate(): Either[Seq[String], ServiceDescription] = {
    if (isValid) {
      Right(serviceDescription.get)
    } else {
      Left(errors)
    }
  }

  private lazy val internalServiceDescription: Option[InternalServiceDescription] = {
    try {
      Some(InternalServiceDescription(apiJson))
    } catch {
      case e: JsonParseException => {
        parseError = Some(e.getMessage)
        None
      }
      case e: JsonProcessingException => {
        parseError = Some(e.getMessage)
        None
      }
      case e: JsonMappingException => {
        parseError = Some(e.getMessage)
        None
      }
      case e: Throwable => throw e
    }
  }

  lazy val errors: Seq[String] = {
    internalServiceDescription match {

      case None => {
        if (apiJson == "") {
          Seq("No Data")
        } else {
          Seq(parseError.getOrElse("Invalid JSON"))
        }
      }

      case Some(sd: InternalServiceDescription) => {
        val requiredFieldErrors = validateRequiredFields()

        if (requiredFieldErrors.isEmpty) {
          validateName ++
          validateBaseUrl ++
          validateModels ++
          validateEnums ++
          validateHeaders ++
          validateModelAndEnumNamesAreDistinct ++
          validateFields ++
          validateParameterTypes ++
          validateFieldTypes ++
          validateFieldDefaults ++
          validateOperations ++
          validateResources ++
          validateParameterBodies ++
          validateParameters ++
          validateResponses ++
          validatePathParameters ++
          validatePathParametersAreRequired

        } else {
          requiredFieldErrors
        }
      }
    }
  }

  def isValid: Boolean = {
    errors.isEmpty
  }


  private def validateName(): Seq[String] = {
    val name = internalServiceDescription.get.name.getOrElse(sys.error("Missing name"))
    if (Text.startsWithLetter(name)) {
      Seq.empty
    } else {
      Seq(s"Name[${name}] must start with a letter")
    }
  }

  private def validateBaseUrl(): Seq[String] = {
    val baseUrl = internalServiceDescription.get.baseUrl
    baseUrl match {
      case Some(url) => { 
        if(url.endsWith("/")){
          Seq(s"base_url[$url] must not end with a '/'")  
        } else {
          Seq.empty
        } 
      }
      case None => Seq.empty
    }
  }

  /**
   * Validate basic structure, returning a list of error messages
   */
  private def validateRequiredFields(): Seq[String] = {
    val missing = RequiredFields.filter { field =>
      (internalServiceDescription.get.json \ field).asOpt[JsValue] match {
        case None => true
        case Some(_) => false
      }
    }
    if (missing.isEmpty) {
      Seq.empty
    } else {
      Seq("Missing: " + missing.mkString(", "))
    }
  }

  /**
   * Validate references to ensure they refer to proper data
   */
  private def validateFieldTypes(): Seq[String] = {
    internalServiceDescription.get.models.flatMap { model =>
      model.fields.filter { f => !f.fieldtype.isEmpty && !f.name.isEmpty }.flatMap { field =>
        val name = field.fieldtype.get
        Datatype.findByName(name) match {
          case None => {
            internalServiceDescription.get.models.find { _.name == name } match {
              case None => {
                internalServiceDescription.get.enums.find { _.name == name } match {
                  case None => Some(s"${model.name}.${field.name.get} has invalid type. There is no model, enum, nor datatype named[$name]")
                  case Some(_) => None
                }
              }
              case Some(_) => None
            }
          }
          case Some(_) => None
        }
      }
    }
  }

  /**
   * Validates that any defaults specified for fields are valid:
   *   Valid based on the datatype
   *   If an enum, the default is listed as a value for that enum
   */
  private def validateFieldDefaults(): Seq[String] = {
    internalServiceDescription.get.models.flatMap { model =>
      model.fields.filter { f => !f.fieldtype.isEmpty && !f.name.isEmpty && !f.default.isEmpty }.flatMap { field =>
        val name = field.fieldtype.get
        Datatype.findByName(name) match {
          case None => {
            internalServiceDescription.get.enums.find(_.name == name) match {
              case None => None
              case Some(enum) => {
                enum.values.filter(!_.name.isEmpty).map(_.name.get) match {
                  case Nil => None
                  case values => {
                    if (values.contains(field.default.get)) {
                      None
                    } else {
                      Some(s"Model[${model.name}] field[${field.name.get}] Default[${field.default.get}] is not valid. Must be one of: " + values.mkString(", "))
                    }
                  }
                }
              }
            }
          }

          case Some(dt) => {
            if (isValid(dt, field.default.get)) {
              None
            } else {
              Some(s"Model[${model.name}] field[${field.name.get}] Default[${field.default.get}] is not valid for datatype[$name]")
            }
          }
        }
      }
    }
  }

  private def isValid(datatype: Datatype, value: String): Boolean = {
    try {
      assertValidDefault(datatype: Datatype, value: String)
      true
    } catch {
      case e: Throwable => {
        false
      }
    }
  }

  private[this] val dateTimeISOParser = ISODateTimeFormat.dateTimeParser()

  private val BooleanValues = Seq("true", "false")

  private def assertValidDefault(datatype: Datatype, value: String) {
    datatype match {
      case Datatype.BooleanType => {
        if (!BooleanValues.contains(value)) {
          sys.error(s"Invalid default[${value}] for boolean. Must be one of: ${BooleanValues.mkString(" ")}")
        }
      }

      case Datatype.MapType => {
        Json.parse(value).asOpt[JsObject] match {
          case None => {
            sys.error(s"Invalid default[${value}] for type object. Must be a valid JSON Object")
          }
          case Some(o) => {}
        }
      }

      case Datatype.IntegerType => {
        value.toInt
      }

      case Datatype.LongType => {
        value.toLong
      }

      case Datatype.DecimalType => {
        BigDecimal(value)
      }

      case Datatype.UnitType => {
        value == ""
      }

      case Datatype.UuidType => {
        UUID.fromString(value)
      }

      case Datatype.DateTimeIso8601Type => {
        dateTimeISOParser.parseDateTime(value)
      }

      case Datatype.DateIso8601Type => {
        dateTimeISOParser.parseDateTime(s"${value}T00:00:00Z")
      }

      case Datatype.StringType => ()

      case Datatype.DoubleType => value.toDouble

    }
  }

  private def validateModels(): Seq[String] = {
    val nameErrors = internalServiceDescription.get.models.flatMap { model =>
      Text.validateName(model.name) match {
        case Nil => None
        case errors => {
          Some(s"Model[${model.name}] name is invalid: ${errors.mkString(" ")}")
        }
      }
    }

    val fieldErrors = internalServiceDescription.get.models.filter { _.fields.isEmpty }.map { model =>
      s"Model[${model.name}] must have at least one field"
    }

    val duplicates = internalServiceDescription.get.models.groupBy(_.name.toLowerCase).filter { _._2.size > 1 }.keys.map { modelName =>
      s"Model[$modelName] appears more than once"
    }

    nameErrors ++ fieldErrors ++ duplicates
  }

  private def validateEnums(): Seq[String] = {
    val nameErrors = internalServiceDescription.get.enums.flatMap { enum =>
      Text.validateName(enum.name) match {
        case Nil => None
        case errors => {
          Some(s"Enum[${enum.name}] name is invalid: ${errors.mkString(" ")}")
        }
      }
    }

    val valueErrors = internalServiceDescription.get.enums.filter { _.values.isEmpty }.map { enum =>
      s"Enum[${enum.name}] must have at least one value"
    }

    val valuesWithoutNames = internalServiceDescription.get.enums.flatMap { enum =>
      enum.values.filter(_.name.isEmpty).map { value =>
        s"Enum[${enum.name}] - all values must have a name"
      }
    }

    val valuesWithInvalidNames = internalServiceDescription.get.enums.flatMap { enum =>
      enum.values.filter(v => !v.name.isEmpty && !Text.startsWithLetter(v.name.get)).map { value =>
        s"Enum[${enum.name}] value[${value.name.get}] is invalid: must start with a letter"
      }
    }

    val duplicates = internalServiceDescription.get.enums.groupBy(_.name.toLowerCase).filter { _._2.size > 1 }.keys.map { enumName =>
      s"Enum[$enumName] appears more than once"
    }

    nameErrors ++ valueErrors ++ valuesWithoutNames ++ valuesWithInvalidNames ++ duplicates
  }

  private def validateHeaders(): Seq[String] = {
    val enumNames = internalServiceDescription.get.enums.map(_.name).toSet

    val headersWithoutNames = internalServiceDescription.get.headers.filter(_.name.isEmpty) match {
      case Nil => Seq.empty
      case headers => Seq("All headers must have a name")
    }

    val headersWithoutTypes = internalServiceDescription.get.headers.filter(_.headertype.isEmpty) match {
      case Nil => Seq.empty
      case headers => Seq("All headers must have a type")
    }

    val headersWithInvalidTypes = internalServiceDescription.get.headers.filter(h => !h.name.isEmpty && !h.headertype.isEmpty).flatMap { header =>
      val htype = header.headertype.get
      if (htype == Datatype.StringType.name || enumNames.contains(htype)) {
        Seq.empty
      } else {
        Seq(s"Header[${header.name.get}] type[$htype] is invalid: Must be a string or the name of an enum")
      }
    }

    val duplicates = internalServiceDescription.get.headers.filter(!_.name.isEmpty).groupBy(_.name.get.toLowerCase).filter { _._2.size > 1 }.keys.map { headerName =>
      s"Header[$headerName] appears more than once"
    }

    headersWithoutNames ++ headersWithoutTypes ++ headersWithInvalidTypes ++ duplicates
  }

  /**
    * While not strictly necessary, we do this to reduce
    * confusion. Otherwise we would require an extension to api.json
    * to indicate if a type referenced a model or an enum. By keeping
    * them distinct, we can avoid any confusion.
    */
  private def validateModelAndEnumNamesAreDistinct(): Seq[String] = {
    val modelNames = internalServiceDescription.get.models.map(_.name.toLowerCase)
    val enumNames = internalServiceDescription.get.enums.map(_.name.toLowerCase).toSet

    modelNames.filter { enumNames.contains(_) }.map { name =>
      s"Name[$name] cannot be used as the name of both a model and an enum"
    }
  }

  private def validateFields(): Seq[String] = {
    val missingTypes = internalServiceDescription.get.models.flatMap { model =>
      model.fields.filter { _.fieldtype.isEmpty }.map { f =>
        s"Model[${model.name}] field[${f.name.get}] must have a type"
      }
    }
    val missingNames = internalServiceDescription.get.models.flatMap { model =>
      model.fields.filter { _.name.isEmpty }.map { f =>
        s"Model[${model.name}] field[${f.name}] must have a name"
      }
    }
    val badNames = internalServiceDescription.get.models.flatMap { model =>
      model.fields.flatMap { f =>
        f.name.map { n => n -> Text.validateName(n) }
      }.filter(_._2.nonEmpty).flatMap { case (name, errors) =>
          errors.map { e =>
            s"Model[${model.name}] field[${name}]: $e"
          }
      }
    }

    val warnings = internalServiceDescription.get.models.flatMap { model =>
      model.fields.filter(f => !f.warnings.isEmpty && !f.name.isEmpty).map { f =>
        s"Model[${model.name}] field[${f.name.get}]: " + f.warnings.mkString(", ")
      }
    }

    missingTypes ++ missingNames ++ badNames ++ warnings
  }

  private def validateResponses(): Seq[String] = {
    val invalidCodes = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.flatMap { r =>
          try {
            r.code.toInt
            None
          } catch {
            case e: java.lang.NumberFormatException => {
              Some(s"Resource[${resource.modelName.getOrElse("")}] ${op.label}: Response code is not an integer[${r.code}]")
            }
          }
        }
      }
    }

    val modelNames = internalServiceDescription.get.models.map { _.name }.toSet
    val enumNames = internalServiceDescription.get.enums.map { _.name }.toSet

    val missingTypes = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.flatMap { r =>
          r.datatype match {
            case None => {
              Some(s"Resource[${resource.modelName.getOrElse("")}] ${op.label} with response code[${r.code}]: Missing type")
            }
            case Some(typeName: String) => {
              Datatype.findByName(typeName) match {
                case Some(dt: Datatype) => {
                  None
                }
                case None => {
                  if (modelNames.contains(typeName) || enumNames.contains(typeName)) {
                    None
                  } else {
                    Some(s"Resource[${resource.modelName.getOrElse("")}] ${op.label} with response code[${r.code}] has an invalid type[${typeName}]. Must be one of: ${ValidDatatypes.mkString(" ")} or the name of an enum or model")
                  }
                }
              }
            }
          }
        }
      }
    }

    val mixed2xxResponseTypes = if (invalidCodes.isEmpty) {
      internalServiceDescription.get.resources.filter { !_.modelName.isEmpty }.flatMap { resource =>
        resource.operations.flatMap { op =>
          val types = op.responses.filter { r => !r.datatypeLabel.isEmpty && r.code.toInt >= 200 && r.code.toInt < 300 }.map(_.datatypeLabel.get).distinct
          if (types.size <= 1) {
            None
          } else {
            Some(s"Resource[${resource.modelName.get}] cannot have varying response types for 2xx response codes: ${types.sorted.mkString(", ")}")
          }
        }
      }
    } else {
      Seq.empty
    }

    val typesNotAllowed = Seq(404) // also >= 500
    val responsesWithDisallowedTypes = if (invalidCodes.isEmpty) {
      internalServiceDescription.get.resources.filter { !_.modelName.isEmpty }.flatMap { resource =>
        resource.operations.flatMap { op =>
          op.responses.find { r => typesNotAllowed.contains(r.code.toInt) || r.code.toInt >= 500 } match {
            case None => {
              None
            }
            case Some(r) => {
              Some(s"Resource[${resource.modelName.get}] ${op.label} has a response with code[${r.code}] - this code cannot be explicitly specified")
            }
          }
        }
      }
    } else {
      Seq.empty
    }

    val typesRequiringUnit = Seq(204, 304)
    val noContentWithTypes = if (invalidCodes.isEmpty) {
      internalServiceDescription.get.resources.filter { !_.modelName.isEmpty }.flatMap { resource =>
        resource.operations.flatMap { op =>
          op.responses.filter(r => typesRequiringUnit.contains(r.code.toInt) && !r.datatype.isEmpty && r.datatype.get != Datatype.UnitType.name).map { r =>
            s"Resource[${resource.modelName.get}] ${op.label} Responses w/ code[${r.code}] must return unit and not[${r.datatype.get}]"
          }
        }
      }
    } else {
      Seq.empty
    }

    val warnings = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.responses.filter(r => !r.warnings.isEmpty).map { r =>
          s"Resource[${resource.modelName.get}] ${op.method.getOrElse("")} ${r.code}: " + r.warnings.mkString(", ")
        }
      }
    }

    invalidCodes ++ missingTypes ++ mixed2xxResponseTypes ++ responsesWithDisallowedTypes ++ noContentWithTypes ++ warnings
  }

  private lazy val ValidDatatypes = Datatype.All.map(_.name).sorted
  private lazy val ValidQueryDatatypes = Datatype.QueryParameterTypes.map(_.name).sorted

  private def validateParameterBodies(): Seq[String] = {
    val modelNames = internalServiceDescription.get.models.map(_.name).toSet
    val enumNames = internalServiceDescription.get.enums.map(_.name).toSet

    val typesNotFound = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.filter(op => !op.body.isEmpty && !modelNames.contains(op.body.get.name) && !enumNames.contains(op.body.get.name) && Datatype.findByName(op.body.get.name).isEmpty).map { op =>
        s"Resource[${resource.modelName.getOrElse("")}] ${op.label} body: Model named[${op.body.get.name}] not found"
      }
    }

    val invalidMethods = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.filter(op => !op.body.isEmpty && !op.method.isEmpty && !Util.isJsonDocumentMethod(op.method.get)).map { op =>
        s"Resource[${resource.modelName.getOrElse("")}] ${op.label}: Cannot specify body for HTTP method[${op.method.get}]"
      }
    }

    typesNotFound ++ invalidMethods
  }

  private def validateParameters(): Seq[String] = {
    val missingNames = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.parameters.filter { p => p.name.isEmpty }.map { p =>
          s"Resource[${resource.modelName.getOrElse("")}] ${op.method.get} ${op.path}: All parameters must have a name"
        }
      }
    }

    val missingTypes = internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.filter(!_.method.isEmpty).flatMap { op =>
        val types = if (op.body.isEmpty && Util.isJsonDocumentMethod(op.method.get)) { 
          ValidDatatypes ++
          internalServiceDescription.get.models.filter(!_.name.isEmpty).map(_.name) ++
          internalServiceDescription.get.enums.filter(!_.name.isEmpty).map(_.name)
        } else {
          ValidQueryDatatypes ++
          internalServiceDescription.get.enums.filter(!_.name.isEmpty).map(_.name)
        }

        op.parameters.filter { !_.name.isEmpty }.flatMap { p =>
          if (p.paramtype.isEmpty) {
            Some(s"Resource[${resource.modelName.getOrElse("")}] ${op.method.get} ${op.path}: Parameter[${p.name.get}] is missing a type. Must be one of: ${types.mkString(" ")}")
          } else if (!types.contains(p.paramtype.get)) {
            Some(s"Resource[${resource.modelName.getOrElse("")}] ${op.method.get} ${op.path}: Parameter[${p.name.get}] has an invalid type[${p.paramtype.get}]. Must be one of: ${types.mkString(" ")}")
          } else {
            None
          }
        }
      }
    }

    missingNames ++ missingTypes
  }

  private def validateParameterTypes(): Seq[String] = {
    internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.flatMap { op =>
        op.parameters.filter( !_.paramtype.isEmpty ).flatMap { param =>

          val typeName = param.paramtype.get

          Datatype.findByName(typeName) match {

            case Some(dt: Datatype) => None

            case None => {
              val model = internalServiceDescription.get.models.find(_.name == typeName)
              val enum = internalServiceDescription.get.enums.find(_.name == typeName)

              if (model.isEmpty && enum.isEmpty) {
                Some(s"Resource[${resource.modelName.getOrElse("")}] ${op.method.get} ${op.path}: Parameter[${param.name.get}] has an invalid datatype[${typeName}]. Must be one of: ${ValidDatatypes.mkString(" ")} or the name of an enum or model")
              } else {
                None
              }
            }
          }
        }
      }
    }
  }

  private def validateOperations(): Seq[String] = {
    internalServiceDescription.get.resources.flatMap { resource =>
      resource.operations.filter(!_.warnings.isEmpty).map { op =>
        s"Resource[${resource.modelName.getOrElse("")}] ${op.label}: " + op.warnings.mkString(", ")
      }
    }
  }

  private def validateResources(): Seq[String] = {
    val modelNameErrors = internalServiceDescription.get.resources.flatMap { res =>
      res.modelName match {
        case None => Some("All resources must have a model")
        case Some(name: String) => {
          internalServiceDescription.get.models.find { _.name == name } match {
            case None => Some(s"Resource[${res.modelName.getOrElse("")}] model name[${name}] is invalid - model not found")
            case Some(_) => None
          }
        }
      }
    }

    val missingOperations = internalServiceDescription.get.resources.filter { _.operations.isEmpty }.map { res =>
      s"Resource[${res.modelName.getOrElse("")}] must have at least one operation"
    }

    val duplicateModels = internalServiceDescription.get.resources.filter { !_.modelName.isEmpty }.flatMap { r =>
      val numberResources = internalServiceDescription.get.resources.filter { _.modelName == r.modelName }.size
      if (numberResources <= 1) {
        None
      } else {
        Some(s"Model[${r.modelName.get}] cannot be mapped to more than one resource")
      }
    }.distinct

    modelNameErrors ++ missingOperations ++ duplicateModels
  }

  private def validatePathParameters(): Seq[String] = {
    val enums = internalServiceDescription.get.enums.map(_.name).toSet

    internalServiceDescription.get.resources.filter(!_.modelName.isEmpty).flatMap { resource =>
      internalServiceDescription.get.models.find(_.name == resource.modelName.get) match {
        case None => None
        case Some(model: InternalModel) => {
          resource.operations.filter(!_.namedPathParameters.isEmpty).flatMap { op =>
            val fieldMap = model.fields.filter(f => !f.name.isEmpty && !f.fieldtype.isEmpty).map(f => (f.name.get -> f.fieldtype.get)).toMap
            val paramMap = op.parameters.filter(p => !p.name.isEmpty && !p.paramtype.isEmpty).map(p => (p.name.get -> p.paramtype.get)).toMap

            op.namedPathParameters.flatMap { name =>
              val typeName = paramMap.get(name).getOrElse {
                fieldMap.get(name).getOrElse {
                  Datatype.StringType.name
                }
              }

              Datatype.findByName(typeName) match {
                case Some(Datatype.BooleanType) => None
                case Some(Datatype.DecimalType) => None
                case Some(Datatype.IntegerType) => None
                case Some(Datatype.DoubleType) => None
                case Some(Datatype.LongType) => None
                case Some(Datatype.StringType) => None
                case Some(Datatype.UuidType) => None
                case Some(Datatype.DateIso8601Type) => None
                case Some(Datatype.DateTimeIso8601Type) => None
                case _ => {
                  if (enums.contains(typeName)) {
                    // Enums are treated as strings here
                    None
                  } else {
                    Some(s"Resource[${resource.modelName.get}] ${op.method.getOrElse("")} path parameter[$name] has an invalid type[$typeName]. Only numbers and strings can be specified as path parameters")
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private def validatePathParametersAreRequired(): Seq[String] = {
    internalServiceDescription.get.resources.filter(!_.modelName.isEmpty).flatMap { resource =>
      internalServiceDescription.get.models.find(_.name == resource.modelName.get) match {
        case None => None
        case Some(model: InternalModel) => {
          resource.operations.filter(!_.namedPathParameters.isEmpty).flatMap { op =>
            val fieldMap = model.fields.filter(f => !f.name.isEmpty && !f.fieldtype.isEmpty).map(f => (f.name.get -> f.required)).toMap
            val paramMap = op.parameters.filter(p => !p.name.isEmpty && !p.paramtype.isEmpty).map(p => (p.name.get -> p.required)).toMap

            op.namedPathParameters.flatMap { name =>
              val isRequired = paramMap.get(name).getOrElse {
                fieldMap.get(name).getOrElse {
                  true
                }
              }

              if (isRequired) {
                None
              } else {
                Some(s"Resource[${resource.modelName.get}] ${op.method.getOrElse("")} path parameter[$name] is specified as optional. All path parameters are required")
              }
            }
          }
        }
      }
    }
  }
}
