package com.wavesplatform.it.api

import java.io.IOException
import java.util.concurrent.TimeoutException

import com.wavesplatform.features.api.ActivationStatus
import com.wavesplatform.it.util.GlobalTimer.{instance => timer}
import com.wavesplatform.it.util._
import com.wavesplatform.matcher.api.CancelOrderRequest
import com.wavesplatform.state2.{ByteStr, Portfolio}
import org.asynchttpclient.Dsl.{get => _get, post => _post}
import org.asynchttpclient._
import org.asynchttpclient.util.HttpConstants
import org.slf4j.LoggerFactory
import play.api.libs.json.Json.{parse, stringify, toJson}
import play.api.libs.json._
import scorex.api.http.PeersApiRoute.{ConnectReq, connectFormat}
import scorex.api.http.alias.CreateAliasRequest
import scorex.api.http.assets._
import scorex.api.http.leasing.{LeaseCancelRequest, LeaseRequest}
import scorex.transaction.assets.exchange.Order
import scorex.utils.{LoggerFacade, ScorexLogging}
import scorex.waves.http.DebugApiRoute._
import scorex.waves.http.DebugMessage._
import scorex.waves.http.{DebugMessage, RollbackParams}

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait NodeApi {

  import NodeApi._

  def restAddress: String

  def nodeRestPort: Int

  def matcherRestPort: Int

  def blockDelay: FiniteDuration

  protected def client: AsyncHttpClient

  protected val log: LoggerFacade = LoggerFacade(LoggerFactory.getLogger(s"${getClass.getName} $restAddress"))

  def matcherGet(path: String, f: RequestBuilder => RequestBuilder = identity, statusCode: Int = HttpConstants.ResponseStatusCodes.OK_200): Future[Response] =
    retrying(f(_get(s"http://$restAddress:$matcherRestPort$path")).build(), statusCode = statusCode)

  def matcherGetWithSignature(path: String, ts: Long, signature: ByteStr, f: RequestBuilder => RequestBuilder = identity): Future[Response] = retrying {
    _get(s"http://$restAddress:$matcherRestPort$path")
      .setHeader("Timestamp", ts)
      .setHeader("Signature", signature)
      .build()
  }

  def matcherGetStatusCode(path: String, statusCode: Int): Future[MessageMatcherResponse] =
    matcherGet(path, statusCode = statusCode).as[MessageMatcherResponse]

  def matcherPost[A: Writes](path: String, body: A): Future[Response] =
    post(s"http://$restAddress", matcherRestPort, path,
      (rb: RequestBuilder) => rb.setHeader("Content-type", "application/json").setBody(stringify(toJson(body))))

  def getOrderStatus(asset: String, orderId: String): Future[MatcherStatusResponse] =
    matcherGet(s"/matcher/orderbook/$asset/WAVES/$orderId").as[MatcherStatusResponse]

  def getOrderBook(asset: String): Future[OrderBookResponse] =
    matcherGet(s"/matcher/orderbook/$asset/WAVES").as[OrderBookResponse]

  def getOrderbookByPublicKey(publicKey: String, timestamp: Long, signature: ByteStr): Future[Seq[OrderbookHistory]] =
    matcherGetWithSignature(s"/matcher/orderbook/$publicKey", timestamp, signature).as[Seq[OrderbookHistory]]

  def get(path: String, f: RequestBuilder => RequestBuilder = identity): Future[Response] =
    retrying(f(_get(s"http://$restAddress:$nodeRestPort$path")).build())

  def getWithApiKey(path: String, f: RequestBuilder => RequestBuilder = identity): Future[Response] = retrying {
    _get(s"http://$restAddress:$nodeRestPort$path")
      .setHeader("api_key", "integration-test-rest-api")
      .build()
  }

  def postJsonWithApiKey[A: Writes](path: String, body: A): Future[Response] = retrying {
    _post(s"http://$restAddress:$nodeRestPort$path")
      .setHeader("api_key", "integration-test-rest-api")
      .setHeader("Content-type", "application/json").setBody(stringify(toJson(body)))
      .build()
  }

  def post(url: String, port: Int, path: String, f: RequestBuilder => RequestBuilder = identity): Future[Response] =
    retrying(f(
      _post(s"$url:$port$path").setHeader("api_key", "integration-test-rest-api")
    ).build())

  def postJson[A: Writes](path: String, body: A): Future[Response] =
    post(path, stringify(toJson(body)))

  def post(path: String, body: String): Future[Response] =
    post(s"http://$restAddress", nodeRestPort, path,
      (rb: RequestBuilder) => rb.setHeader("Content-type", "application/json").setBody(body))

  def blacklist(networkIpAddress: String, hostNetworkPort: Int): Future[Unit] =
    post("/debug/blacklist", s"$networkIpAddress:$hostNetworkPort").map(_ => ())

  def printDebugMessage(db: DebugMessage): Future[Response] = postJsonWithApiKey("/debug/print", db)

  def connectedPeers: Future[Seq[Peer]] = get("/peers/connected").map { r =>
    (Json.parse(r.getResponseBody) \ "peers").as[Seq[Peer]]
  }

  def blacklistedPeers: Future[Seq[BlacklistedPeer]] = get("/peers/blacklisted").map { r =>
    Json.parse(r.getResponseBody).as[Seq[BlacklistedPeer]]
  }

  def connect(host: String, port: Int): Future[Unit] = postJson("/peers/connect", ConnectReq(host, port)).map(_ => ())

  def waitForStartup(): Future[Option[Response]] = {
    val timeout = 500

    val request = _get(s"http://$restAddress:$nodeRestPort/blocks/height")
      .setReadTimeout(timeout)
      .setRequestTimeout(timeout)
      .build()

    def send(n: NodeApi) = client
      .executeRequest(request).toCompletableFuture.toScala
      .map(Option(_))
      .recoverWith {
        case e@(_: IOException | _: TimeoutException) => Future(None)
      }
    def cond(ropt: Option[Response]) = ropt.exists { r =>
      r.getStatusCode == HttpConstants.ResponseStatusCodes.OK_200 && (Json.parse(r.getResponseBody) \ "height").as[Int] > 0
    }
    waitFor("node is up")(send, cond, 1.second)
  }

  def waitForPeers(targetPeersCount: Int): Future[Seq[Peer]] = waitFor[Seq[Peer]](s"connectedPeers.size >= $targetPeersCount")(_.connectedPeers, _.size >= targetPeersCount, 1.second)

  def height: Future[Int] = get("/blocks/height").as[JsValue].map(v => (v \ "height").as[Int])

  def blockAt(height: Int) = get(s"/blocks/at/$height").as[Block]

  def utx = get(s"/transactions/unconfirmed").as[Seq[Transaction]]

  def utxSize = get(s"/transactions/unconfirmed/size").as[JsObject].map(_.value("size").as[Int])

  def lastBlock: Future[Block] = get("/blocks/last").as[Block]

  def blockSeq(from: Int, to: Int) = get(s"/blocks/seq/$from/$to").as[Seq[Block]]

  def blockHeadersAt(height: Int) = get(s"/blocks/headers/at/$height").as[BlockHeaders]

  def blockHeadersSeq(from: Int, to: Int) = get(s"/blocks/headers/seq/$from/$to").as[Seq[BlockHeaders]]

  def lastBlockHeaders: Future[BlockHeaders] = get("/blocks/headers/last").as[BlockHeaders]

  def status: Future[Status] = get("/node/status").as[Status]

  def activationStatus: Future[ActivationStatus] = get("/activation/status").as[ActivationStatus]

  def balance(address: String): Future[Balance] = get(s"/addresses/balance/$address").as[Balance]

  def findTransactionInfo(txId: String): Future[Option[Transaction]] = transactionInfo(txId).transform {
    case Success(tx) => Success(Some(tx))
    case Failure(UnexpectedStatusCodeException(_, r)) if r.getStatusCode == 404 => Success(None)
    case Failure(ex) => Failure(ex)
  }

  def waitForTransaction(txId: String, retryInterval: FiniteDuration = 1.second): Future[Transaction] = waitFor[Option[Transaction]](s"transaction $txId")(_.transactionInfo(txId).transform {
    case Success(tx) => Success(Some(tx))
    case Failure(UnexpectedStatusCodeException(_, r)) if r.getStatusCode == 404 => Success(None)
    case Failure(ex) => Failure(ex)
  }, tOpt => tOpt.exists(_.id == txId), retryInterval).map(_.get)

  def waitForUtxIncreased(fromSize: Int): Future[Int] = waitFor[Int](s"utxSize > $fromSize")(
    _.utxSize,
    _ > fromSize,
    100.millis
  )

  def waitForHeight(expectedHeight: Int): Future[Int] = waitFor[Int](s"height >= $expectedHeight")(_.height, h => h >= expectedHeight, 1.second)

  def transactionInfo(txId: String): Future[Transaction] = get(s"/transactions/info/$txId").as[Transaction]

  def effectiveBalance(address: String): Future[Balance] = get(s"/addresses/effectiveBalance/$address").as[Balance]

  def transfer(sourceAddress: String, recipient: String, amount: Long, fee: Long, assetId: Option[String] = None): Future[Transaction] =
    postJson("/assets/transfer", TransferRequest(assetId, None, amount, fee, sourceAddress, None, recipient)).as[Transaction]

  def payment(sourceAddress: String, recipient: String, amount: Long, fee: Long): Future[Transaction] =
    postJson("/waves/payment", PaymentRequest(amount, fee, sourceAddress, recipient)).as[Transaction]

  def lease(sourceAddress: String, recipient: String, amount: Long, fee: Long): Future[Transaction] =
    postJson("/leasing/lease", LeaseRequest(sourceAddress, amount, fee, recipient)).as[Transaction]

  def cancelLease(sourceAddress: String, leaseId: String, fee: Long): Future[Transaction] =
    postJson("/leasing/cancel", LeaseCancelRequest(sourceAddress, leaseId, fee)).as[Transaction]

  def issue(sourceAddress: String, name: String, description: String, quantity: Long, decimals: Byte, reissuable: Boolean, fee: Long): Future[Transaction] =
    postJson("/assets/issue", IssueRequest(sourceAddress, name, description, quantity, decimals, reissuable, fee)).as[Transaction]

  def reissue(sourceAddress: String, assetId: String, quantity: Long, reissuable: Boolean, fee: Long): Future[Transaction] =
    postJson("/assets/reissue", ReissueRequest(sourceAddress, assetId, quantity, reissuable, fee)).as[Transaction]

  def burn(sourceAddress: String, assetId: String, quantity: Long, fee: Long): Future[Transaction] =
    postJson("/assets/burn", BurnRequest(sourceAddress, assetId, quantity, fee)).as[Transaction]

  def assetBalance(address: String, asset: String): Future[AssetBalance] =
    get(s"/assets/balance/$address/$asset").as[AssetBalance]

  def assetsBalance(address: String): Future[FullAssetsInfo] =
    get(s"/assets/balance/$address").as[FullAssetsInfo]

  def transfer(sourceAddress: String, recipient: String, amount: Long, fee: Long): Future[Transaction] =
    postJson("/assets/transfer", TransferRequest(None, None, amount, fee, sourceAddress, None, recipient)).as[Transaction]

  def signedTransfer(transfer: SignedTransferRequest): Future[Transaction] =
    postJson("/assets/broadcast/transfer", transfer).as[Transaction]

  def batchSignedTransfer(transfers: Seq[SignedTransferRequest], timeout: FiniteDuration = 1.minute): Future[Seq[Transaction]] = {
    val request = _post(s"http://$restAddress:$nodeRestPort/assets/broadcast/batch-transfer")
      .setHeader("Content-type", "application/json")
      .setHeader("api_key", "integration-test-rest-api")
      .setReadTimeout(timeout.toMillis.toInt)
      .setRequestTimeout(timeout.toMillis.toInt)
      .setBody(stringify(toJson(transfers)))
      .build()

    def aux: Future[Response] = once(request)
      .flatMap { response =>
        if (response.getStatusCode == 503) throw new IOException(s"Unexpected status code: 503")
        else Future.successful(response)
      }
      .recoverWith {
        case e@(_: IOException | _: TimeoutException) =>
          log.debug(s"Failed to send ${transfers.size} txs: ${e.getMessage}")
          timer.schedule(aux, 20.seconds)
      }

    aux.as[Seq[Transaction]]
  }

  def createAlias(targetAddress: String, alias: String, fee: Long): Future[Transaction] =
    postJson("/alias/create", CreateAliasRequest(targetAddress, alias, fee)).as[Transaction]

  def aliasByAddress(targetAddress: String) =
    get(s"/alias/by-address/$targetAddress").as[Seq[String]]

  def addressByAlias(targetAlias: String): Future[Address] =
    get(s"/alias/by-alias/$targetAlias").as[Address]

  def rollback(to: Int, returnToUTX: Boolean = true): Future[Unit] =
    postJson("/debug/rollback", RollbackParams(to, returnToUTX)).map(_ => ())

  def ensureTxDoesntExist(txId: String): Future[Unit] =
    utx.zip(findTransactionInfo(txId)).flatMap({
      case (utx, _) if utx.map(_.id).contains(txId) =>
        Future.failed(new IllegalStateException(s"Tx $txId is in UTX"))
      case (_, txOpt) if txOpt.isDefined =>
        Future.failed(new IllegalStateException(s"Tx $txId is in blockchain"))
      case _ =>
        Future.successful(())
    })

  def waitFor[A](desc: String)(f: this.type => Future[A], cond: A => Boolean, retryInterval: FiniteDuration): Future[A] = {
    log.debug(s"Awaiting condition '$desc'")
    timer.retryUntil(f(this), cond, retryInterval)
      .map(a => {
        log.debug(s"Condition '$desc' met")
        a
      })
  }

  def createAddress: Future[String] =
    post(s"http://$restAddress", nodeRestPort, "/addresses").as[JsValue].map(v => (v \ "address").as[String])

  def waitForNextBlock: Future[Block] = for {
    currentBlock <- lastBlock
    actualBlock <- findBlock(_.height > currentBlock.height, currentBlock.height)
  } yield actualBlock

  def findBlock(cond: Block => Boolean, from: Int = 1, to: Int = Int.MaxValue): Future[Block] = {
    def load(_from: Int, _to: Int): Future[Block] = blockSeq(_from, _to).flatMap { blocks =>
      blocks.find(cond).fold[Future[NodeApi.Block]] {
        val maybeLastBlock = blocks.lastOption
        if (maybeLastBlock.exists(_.height >= to)) {
          Future.failed(new NoSuchElementException)
        } else {
          val newFrom = maybeLastBlock.fold(_from)(b => (b.height + 19).min(to))
          val newTo = newFrom + 19
          log.debug(s"Loaded ${blocks.length} blocks, no match found. Next range: [$newFrom, ${newFrom + 19}]")
          timer.schedule(load(newFrom, newTo), blockDelay)
        }
      }(Future.successful)
    }

    load(from, (from + 19).min(to))
  }

  def getGeneratedBlocks(address: String, from: Long, to: Long): Future[Seq[Block]] =
    get(s"/blocks/address/$address/$from/$to").as[Seq[Block]]

  def issueAsset(address: String, name: String, description: String, quantity: Long, decimals: Byte, fee: Long,
                 reissuable: Boolean): Future[Transaction] =
    postJson("/assets/issue", IssueRequest(address, name, description, quantity, decimals, reissuable, fee)).as[Transaction]

  def placeOrder(order: Order): Future[MatcherResponse] =
    matcherPost("/matcher/orderbook", order.json()).as[MatcherResponse]

  def expectIncorrectOrderPlacement(order: Order, expectedStatusCode: Int, expectedStatus: String): Future[Boolean] =
    matcherPost("/matcher/orderbook", order.json()) transform {
      case Failure(UnexpectedStatusCodeException(_, r)) if r.getStatusCode == expectedStatusCode =>
        Try(parse(r.getResponseBody).as[MatcherStatusResponse]) match {
          case Success(mr) if mr.status == expectedStatus => Success(true)
          case Failure(f) => Failure(new RuntimeException(s"Failed to parse response: $f"))
        }
      case Success(r) => Failure(new RuntimeException(s"Unexpected matcher response: (${r.getStatusCode}) ${r.getResponseBody}"))
      case _ => Failure(new RuntimeException(s"Unexpected failure from matcher"))
    }

  def cancelOrder(amountAsset: String, priceAsset: String, request: CancelOrderRequest): Future[MatcherStatusResponse] =
    matcherPost(s"/matcher/orderbook/$amountAsset/$priceAsset/cancel", request.json).as[MatcherStatusResponse]

  def retrying(r: Request, interval: FiniteDuration = 1.second, statusCode: Int = HttpConstants.ResponseStatusCodes.OK_200): Future[Response] = {
    def executeRequest: Future[Response] = {
      log.trace(s"Executing request '$r'")
      client.executeRequest(r, new AsyncCompletionHandler[Response] {
        override def onCompleted(response: Response): Response = {
          if (response.getStatusCode == statusCode) {
            log.debug(s"Request: ${r.getUrl} \n Response: ${response.getResponseBody}")
            response
          } else {
            log.debug(s"Request:  ${r.getUrl} \n Unexpected status code(${response.getStatusCode}): ${response.getResponseBody}")
            throw UnexpectedStatusCodeException(r, response)
          }
        }
      }).toCompletableFuture.toScala
        .recoverWith {
          case e@(_: IOException | _: TimeoutException) =>
            log.debug(s"Failed to execute request '$r' with error: ${e.getMessage}")
            timer.schedule(executeRequest, interval)
        }
    }

    executeRequest
  }

  def once(r: Request): Future[Response] = {
    log.debug(s"Request: ${r.getUrl}")
    client
      .executeRequest(r, new AsyncCompletionHandler[Response] {
        override def onCompleted(response: Response): Response = {
          log.debug(s"Response for ${r.getUrl} is ${response.getStatusCode}")
          response
        }
      })
      .toCompletableFuture
      .toScala
  }

  def waitForDebugInfoAt(height: Long): Future[DebugInfo] = waitFor[DebugInfo](s"debug info at height >= $height")(_.get("/debug/info").as[DebugInfo], _.stateHeight >= height, 1.seconds)

  def debugStateAt(height: Long): Future[Map[String, Long]] = get(s"/debug/stateWaves/$height").as[Map[String, Long]]

  def debugPortfoliosFor(address: String, considerUnspent: Boolean) = {
    getWithApiKey(s"/debug/portfolios/$address?considerUnspent=$considerUnspent")
  }.as[Portfolio]

}

object NodeApi extends ScorexLogging {

  case class UnexpectedStatusCodeException(request: Request, response: Response) extends Exception(s"Request: ${request.getUrl}\n" +
    s"Unexpected status code (${response.getStatusCode}): ${response.getResponseBody}")

  case class Status(blockchainHeight: Int, stateHeight: Int, updatedTimestamp: Long, updatedDate: String)

  implicit val statusFormat: Format[Status] = Json.format

  case class Peer(address: String, declaredAddress: String, peerName: String)

  implicit val peerFormat: Format[Peer] = Json.format

  case class Address(address: String)

  implicit val addressFormat: Format[Address] = Json.format

  case class Balance(address: String, confirmations: Int, balance: Long)

  implicit val balanceFormat: Format[Balance] = Json.format

  case class AssetBalance(address: String, assetId: String, balance: Long)

  implicit val assetBalanceFormat: Format[AssetBalance] = Json.format

  case class FullAssetInfo(assetId: String, balance: Long, reissuable: Boolean, quantity: Long)

  implicit val fullAssetInfoFormat: Format[FullAssetInfo] = Json.format

  case class FullAssetsInfo(address: String, balances: List[FullAssetInfo])

  implicit val fullAssetsInfoFormat: Format[FullAssetsInfo] = Json.format

  case class Transaction(`type`: Int, id: String, fee: Long, timestamp: Long)

  implicit val transactionFormat: Format[Transaction] = Json.format

  case class Block(signature: String, height: Int, timestamp: Long, generator: String, transactions: Seq[Transaction],
                   fee: Long, features: Option[Seq[Short]])

  case class BlockHeaders(signature: String, height: Int, timestamp: Long, generator: String, transactionCount: Int, blocksize: Int)

  implicit val blockHeadersFormat: Format[BlockHeaders] = Json.format

  implicit val blockFormat: Format[Block] = Json.format

  case class MatcherMessage(id: String)

  implicit val matcherMessageFormat: Format[MatcherMessage] = Json.format

  case class MatcherResponse(status: String, message: MatcherMessage)

  implicit val matcherResponseFormat: Format[MatcherResponse] = Json.format

  case class MatcherStatusResponse(status: String)

  implicit val matcherStatusResponseFormat: Format[MatcherStatusResponse] = Json.format

  case class MessageMatcherResponse(message: String)

  implicit val messageMatcherResponseFormat: Format[MessageMatcherResponse] = Json.format

  case class OrderbookHistory(id: String, `type`: String, amount: Long, price: Long, timestamp: Long, filled: Int,
                              status: String)

  //, assetPair: PairResponse)

  implicit val orderbookHistory: Format[OrderbookHistory] = Json.format

  case class PairResponse(amountAsset: String, priceAsset: String)

  implicit val pairResponseFormat: Format[PairResponse] = Json.format

  case class LevelResponse(price: Long, amount: Long)

  implicit val levelResponseFormat: Format[LevelResponse] = Json.format

  case class OrderBookResponse(timestamp: Long, pair: PairResponse, bids: List[LevelResponse], asks: List[LevelResponse])

  implicit val orderBookResponseFormat: Format[OrderBookResponse] = Json.format

  case class DebugInfo(stateHeight: Long, stateHash: Long)

  implicit val debugInfoFormat: Format[DebugInfo] = Json.format


  case class BlacklistedPeer(hostname: String, timestamp: Long, reason: String)

  implicit val blacklistedPeerFormat: Format[BlacklistedPeer] = Json.format

  // Obsolete payment request
  case class PaymentRequest(amount: Long, fee: Long, sender: String, recipient: String)

  object PaymentRequest {
    implicit val paymentFormat: Format[PaymentRequest] = Json.format
  }
}
