package im.actor.server.api.rpc.service.auth

import akka.actor.ActorSystem

import im.actor.api.rpc._
import im.actor.api.rpc.auth._
import im.actor.api.rpc.misc._
import im.actor.server.api.util
import im.actor.server.models
import im.actor.server.persist

import org.joda.time.DateTime

import scala.concurrent._, forkjoin.ThreadLocalRandom

import scalaz._, std.either._
import shapeless._
import slick.dbio.DBIO
import slick.driver.PostgresDriver.api._

trait AuthServiceImpl extends AuthService with Helpers {
  private trait SignType
  private case class Up(name: String, isSilent: Boolean) extends SignType
  private case object In extends SignType

  val db: Database
  implicit val actorSystem: ActorSystem

  override def handleGetAuthSessions(authId: Long, optUserId: Option[Int]): Future[HandlerResult[ResponseGetAuthSessions]] =
    throw new NotImplementedError()

  override def handleSendAuthCode(
    authId: Long, optUserId: Option[Int], rawPhoneNumber: Long, appId: Int, apiKey: String
  ): Future[HandlerResult[ResponseSendAuthCode]] = {
    util.PhoneNumber.normalizeLong(rawPhoneNumber) match {
      case None =>
        Future.successful(Error(Errors.PhoneNumberInvalid))
      case Some(normPhoneNumber) =>
        val action = persist.AuthSmsCode.findByPhoneNumber(normPhoneNumber).headOption.flatMap {
          case Some(models.AuthSmsCode(_, smsHash, smsCode)) =>
            DBIO.successful(normPhoneNumber :: smsHash :: smsCode :: HNil)
          case None =>
            val smsHash = genSmsHash()
            val smsCode = normPhoneNumber.toString match {
              case strNumber if strNumber.startsWith("7555") => strNumber(4).toString * 4
              case _                                         => genSmsCode()
            }
            for (
              _ <- persist.AuthSmsCode.create(
                phoneNumber = normPhoneNumber, smsHash = smsHash, smsCode = smsCode
              )
            ) yield (normPhoneNumber :: smsHash :: smsCode :: HNil)
        }.flatMap { res =>
          persist.UserPhone.exists(normPhoneNumber) map (res :+ _)
        }.map {
          case number :: smsHash :: smsCode :: isRegistered :: HNil =>
            sendSmsCode(authId, number, smsCode)
            Ok(ResponseSendAuthCode(smsHash, isRegistered), Vector.empty)
        }
        db.run(action.transactionally)
    }
  }

  override def handleSendAuthCall(
    authId: Long, optUserId: Option[Int], phoneNumber: Long, smsHash: String, appId: Int, apiKey: String
  ): Future[HandlerResult[ResponseVoid]] =
    throw new NotImplementedError()

  override def handleSignOut(authId: Long, optUserId: Option[Int]): Future[HandlerResult[ResponseVoid]] =
    throw new NotImplementedError()

  override def handleSignIn(
    authId: Long, optUserId: Option[Int],
    rawPhoneNumber: Long,
    smsHash:     String,
    smsCode:     String,
    publicKey:   Array[Byte],
    deviceHash:  Array[Byte],
    deviceTitle: String,
    appId:       Int,
    appKey:      String
  ): Future[HandlerResult[ResponseAuth]] =
    handleSign(In,
      authId, optUserId, rawPhoneNumber, smsHash, smsCode,
      publicKey, deviceHash, deviceTitle, appId, appKey
    )

  override def handleSignUp(
    authId:         Long,
    optUserId:      Option[Int],
    rawPhoneNumber: Long,
    smsHash:        String,
    smsCode:        String,
    name:           String,
    publicKey:      Array[Byte],
    deviceHash:     Array[Byte],
    deviceTitle:    String,
    appId:          Int,
    appKey:         String,
    isSilent:       Boolean
  ): Future[HandlerResult[ResponseAuth]] =
    handleSign(Up(name, isSilent),
      authId, optUserId, rawPhoneNumber, smsHash, smsCode,
      publicKey, deviceHash, deviceTitle, appId, appKey
    )

  private def handleSign(
    signType:       SignType,
    authId:         Long,
    optUserId:      Option[Int],
    rawPhoneNumber: Long,
    smsHash:        String,
    smsCode:        String,
    rawPublicKey:   Array[Byte],
    deviceHash:     Array[Byte],
    deviceTitle:    String,
    appId:          Int,
    appKey:         String
  ): Future[HandlerResult[ResponseAuth]] = {
    util.PhoneNumber.normalizeWithCountry(rawPhoneNumber) match {
      case None => Future.successful(Error(Errors.PhoneNumberInvalid))
      case Some((normPhoneNumber, countryCode)) =>
        if (smsCode.isEmpty) Future.successful(Error(Errors.PhoneCodeEmpty))
        else if (rawPublicKey.length == 0) Future.successful(Error(Errors.InvalidKey))
        else {
          val action = (for {
            code <- persist.AuthSmsCode.findByPhoneNumber(normPhoneNumber).headOption
            phone <- persist.UserPhone.findByPhoneNumber(normPhoneNumber).headOption
          } yield (code :: phone :: HNil)).flatMap {
            case None :: _ :: HNil => DBIO.successful(Error(Errors.PhoneCodeExpired))
            case Some(smsCodeModel) :: _ :: HNil if smsCodeModel.smsHash != smsHash =>
              DBIO.successful(Error(Errors.PhoneCodeExpired))
            case Some(smsCodeModel) :: _ :: HNil if smsCodeModel.smsCode != smsCode =>
              DBIO.successful(Error(Errors.PhoneCodeInvalid))
            case Some(_) :: optPhone :: HNil =>
              signType match {
                case Up(rawName, isSilent) =>
                  persist.AuthSmsCode.deleteByPhoneNumber(normPhoneNumber).andThen(
                    optPhone match {
                      // Phone does not exist, register the user
                      case None => withValidName(rawName) { name => withValidPublicKey(rawPublicKey) { publicKey =>
                        val rnd = ThreadLocalRandom.current()
                        val (userId, phoneId) = (nextIntId(rnd), nextIntId(rnd))
                        val user = models.User(userId, nextAccessSalt(rnd), name, countryCode, models.NoSex, models.UserState.Registered)

                        for {
                          _ <- persist.User.create(user)
                          _ <- persist.UserPhone.create(phoneId, userId, nextAccessSalt(rnd), normPhoneNumber, "Mobile phone")
                          pkHash = keyHash(publicKey)
                          _ <- persist.UserPublicKey.create(userId, pkHash, publicKey, authId)
                          _ <- persist.AuthId.setUserId(authId, userId)
                          _ <- persist.AvatarData.create(models.AvatarData.empty(models.AvatarData.OfUser, user.id.toLong))
                        } yield {
                          \/-(user :: pkHash :: HNil)
                        }
                      }}
                      // Phone already exists, fall back to SignIn
                      case Some(_) =>
                        throw new Exception("")
                    }
                  )
                case In =>
                  throw new Exception("xa")
              }
          }.flatMap {
            case \/-(user :: pkHash :: HNil) =>
              val rnd = ThreadLocalRandom.current()
              val authSession = models.AuthSession(
                userId = user.id,
                id = nextIntId(rnd),
                authId = authId,
                appId = appId,
                appTitle = models.AuthSession.appTitleOf(appId),
                publicKeyHash = pkHash,
                deviceHash = deviceHash,
                deviceTitle = deviceTitle,
                authTime = DateTime.now,
                authLocation = "",
                latitude = None,
                longitude = None
              )
              // TODO: logout other auth sessions

              persist.AuthSession.create(authSession) andThen util.User.struct(
                user,
                None,
                authId
              ) map { userStruct =>
                Ok(
                  ResponseAuth(
                    pkHash,
                    userStruct,
                    misc.Config(300)
                  ),
                  Vector.empty
                )
              }
            case error @ -\/(_) => DBIO.successful(error)
            //throw new Exception(user.id.toString)
          }

          db.run(action)
        }
    }
  }

  override def handleTerminateAllSessions(authId: Long, optUserId: Option[Int]): Future[HandlerResult[ResponseVoid]] =
    throw new NotImplementedError()

  override def handleTerminateSession(authId: Long, optUserId: Option[Int], id: Int): Future[HandlerResult[ResponseVoid]] =
    throw new NotImplementedError()

  private def sendSmsCode(authId: Long, phoneNumber: Long, code: String): Unit = {

  }

  private def genSmsCode() = ThreadLocalRandom.current.nextLong().toString.dropWhile(c => c == '0' || c == '-').take(6)

  private def genSmsHash() = ThreadLocalRandom.current.nextLong().toString
}
