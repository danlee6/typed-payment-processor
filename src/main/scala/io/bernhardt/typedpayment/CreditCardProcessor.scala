package io.bernhardt.typedpayment

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import io.bernhardt.typedpayment.Configuration.{
  CreditCard,
  MerchantConfiguration,
  MerchantId,
  PaymentMethod,
  TransactionId,
  UserId
}
import io.bernhardt.typedpayment.CreditCardStorage.FindCreditCardResult
import io.bernhardt.typedpayment.Processor.{ ProcessorRequest, RequestProcessed, Transaction }
import squants.market.Money

object CreditCardProcessor {
  def apply(storage: ActorRef[CreditCardStorage.Command[_]]): Behavior[ProcessorRequest] = Behaviors.setup { context =>
    Behaviors.withStash(1000) { stash =>
      // register with the Receptionist which makes this actor discoverable
      context.system.receptionist ! Receptionist.Register(Key, context.self)

      val storageAdapter: ActorRef[FindCreditCardResult] = context.messageAdapter { response =>
        AdaptedStorageResponse(response)
      }

      def handleRequest: Behavior[ProcessorRequest] = {
        Behaviors.receiveMessage {
          case request @ Processor.Process(_, _, _, paymentMethod: CreditCard, _) =>
            storage ! CreditCardStorage.FindById(paymentMethod.storageId, storageAdapter)
            retrievingCard(request)
          case _ => Behaviors.unhandled
        }
      }

      def retrievingCard(request: Processor.Process): Behavior[ProcessorRequest] = {
        Behaviors.receiveMessage {
          case AdaptedStorageResponse(CreditCardStorage.CreditCardFound(_)) =>
            // a real system would go talk to backend systems using the retrieved data
            // but here, we're just going to validate the request, always
            request.sender ! RequestProcessed(
              Transaction(
                TransactionId(UUID.randomUUID().toString),
                LocalDateTime.now,
                request.amount,
                request.userId,
                request.merchantConfiguration.merchantId))

            // we're able to process new requests
            stash.unstashAll(handleRequest)
          case AdaptedStorageResponse(CreditCardStorage.CreditCardNotFound(id)) =>
            context.log.error("Could not find stored card {}", id)
            Behaviors.same
          case other =>
            if (stash.isFull) {
              context.log.warn("Cannot handle more incoming messages, dropping them.")
            } else {
              stash.stash(other)
            }
            Behaviors.same
        }
      }

      handleRequest
    }
  }

  val Key: ServiceKey[ProcessorRequest] = ServiceKey("creditCardProcessor")

  sealed trait InternalMessage extends ProcessorRequest
  final case class AdaptedStorageResponse(response: CreditCardStorage.FindCreditCardResult) extends InternalMessage
}

object Processor {
  sealed trait ProcessorRequest
  final case class Process(
      amount: Money,
      merchantConfiguration: MerchantConfiguration,
      userId: UserId,
      paymentMethod: PaymentMethod,
      sender: ActorRef[ProcessorResponse])
      extends ProcessorRequest

  sealed trait ProcessorResponse
  final case class RequestProcessed(transaction: Transaction) extends ProcessorResponse

  final case class Transaction(
      id: TransactionId,
      timestamp: LocalDateTime,
      amount: Money,
      userId: UserId,
      merchantId: MerchantId)
}
