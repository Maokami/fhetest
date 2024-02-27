package fhetest

import fhetest.Utils.*
import fhetest.Generate.Strategy

class Config(
  var fileName: Option[String] = None,
  var dirName: Option[String] = None,
  var backend: Option[Backend] = None,
  var wordSize: Option[Int] = None,
  var encParams: Option[EncParams] = None,
  var encType: Option[ENC_TYPE] = None,
  var genStrategy: Option[Strategy] = None,
  var genCount: Option[Int] = None,
  var toJson: Boolean = false,
  var sealVersion: String = SEAL_VERSIONS.head,
  var openfheVersion: String = OPENFHE_VERSIONS.head,
)

object Config {
  // each argument is formatted as "-key1:value1 -key2:value2 ..."
  def apply(args: List[String]): Config = {
    val config = new Config()
    args.foreach {
      case arg if arg.startsWith("-") =>
        val Array(key, value) = arg.drop(1).split(":", 2)
        key.toLowerCase() match {
          case "file" => config.fileName = Some(value)
          case "dir"  => config.dirName = Some(value)
          case "b"    => config.backend = parseBackend(value)
          case "w"    => config.wordSize = Some(value.toInt)
          case "n" =>
            config.encParams = Some(
              config.encParams
                .getOrElse(EncParams(0, 0, 0))
                .copy(ringDim = value.toInt),
            )
          case "d" =>
            config.encParams = Some(
              config.encParams
                .getOrElse(EncParams(0, 0, 0))
                .copy(mulDepth = value.toInt),
            )
          case "m" =>
            config.encParams = Some(
              config.encParams
                .getOrElse(EncParams(0, 0, 0))
                .copy(plainMod = value.toInt),
            )
          case "type"  => config.encType = parseEncType(value)
          case "stg"   => config.genStrategy = parseStrategy(value)
          case "count" => config.genCount = Some(value.toInt)
          case "json"  => config.toJson = value.toBoolean
          case "seal" =>
            if SEAL_VERSIONS.contains(value) then config.sealVersion = value
            else throw new Error(s"Unknown SEAL version: $value")
          case "openfhe" =>
            if OPENFHE_VERSIONS.contains(value) then
              config.openfheVersion = value
            else throw new Error(s"Unknown OpenFHE version: $value")
          case _ => throw new Error(s"Unknown option: $key")
        }
      case _ => // 잘못된 형식의 인자 처리
    }
    config
  }
}