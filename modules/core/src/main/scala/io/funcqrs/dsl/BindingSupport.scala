package io.funcqrs.dsl

import io.funcqrs._
import io.funcqrs.behavior.{ FutureCommandHandlerInvoker, TryCommandHandlerInvoker, IdCommandHandlerInvoker, CommandHandlerInvoker }
import io.funcqrs.interpreters._

import scala.collection.immutable
import scala.concurrent.Future
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.{ Failure, Try }

trait BindingSupport {

  def aggregate[A <: AggregateLike]: Binding[A] = DefaultBinding[A]()

  case class DefaultBinding[A <: AggregateLike](
      cmdHandlerInvokers: CommandToInvoker[A#Command, A#Event] = PartialFunction.empty,
      rejectCmdInvokers: CommandToInvoker[A#Command, A#Event] = PartialFunction.empty,
      eventListeners: EventToAggregate[A#Event, A] = PartialFunction.empty
  ) extends Binding[A] {

    /**
     * Declares a guard clause that reject commands that fulfill a given condition
     * @param cmdHandler - a PartialFunction from Command to Throwable.
     * @return - return a [[Binding]].
     */
    def reject(cmdHandler: PartialFunction[Command, Throwable]): Binding[Aggregate] = {

      val invokerPF: CommandToInvoker[Command, A#Event] = {
        case cmd if cmdHandler.isDefinedAt(cmd) =>
          TryCommandHandlerInvoker(cmd => Failure(cmdHandler(cmd)))
      }

      this.copy(
        rejectCmdInvokers = rejectCmdInvokers orElse invokerPF
      )
    }

    def handler[C <: Command: ClassTag, E <: Event](cmdHandler: C => Identity[E]): Binding[Aggregate] = {
      // wrap single event in immutable.Seq
      val handlerWithSeq: C => Identity[immutable.Seq[E]] = (cmd: C) => immutable.Seq(cmdHandler(cmd))
      handler.manyEvents(handlerWithSeq)
    }

    def handler: ManyEventsBinder[Identity] = IdentityManyEventsBinder(this)

    case class IdentityManyEventsBinder(binding: DefaultBinding[A]) extends ManyEventsBinder[Identity] {

      def manyEvents[C <: Command: ClassTag, E <: Event](cmdHandler: (C) => Identity[immutable.Seq[E]]): Binding[Aggregate] = {

        object CmdExtractor extends ClassTagExtractor[C]

        val invokerPF: CommandToInvoker[C, E] = {
          case CmdExtractor(cmd) => IdCommandHandlerInvoker(cmdHandler)
        }

        binding.copy(
          cmdHandlerInvokers = cmdHandlerInvokers orElse invokerPF.asInstanceOf[CommandToInvoker[Command, Event]]
        )
      }
    }

    def tryHandler[C <: Command: ClassTag, E <: Event](cmdHandler: C => Try[E]): Binding[Aggregate] = {
      // wrap single event in immutable.Seq
      val handlerWithSeq: (C) => Try[immutable.Seq[E]] = (cmd: C) => cmdHandler(cmd).map(immutable.Seq(_))
      tryHandler.manyEvents(handlerWithSeq)
    }

    def tryHandler: ManyEventsBinder[Try] = TryManyEventsBinder(this)

    case class TryManyEventsBinder(binding: DefaultBinding[A]) extends ManyEventsBinder[Try] {
      def manyEvents[C <: Command: ClassTag, E <: Event](cmdHandler: (C) => Try[immutable.Seq[E]]): Binding[A] = {

        object CmdExtractor extends ClassTagExtractor[C]

        val invokerPF: CommandToInvoker[C, E] = {
          case CmdExtractor(cmd) => TryCommandHandlerInvoker(cmdHandler)
        }

        //consInvoker: PartialFunction[C, F[immutable.Seq[E]]] => CommandHandlerInvoker[C, E]
        binding.copy(
          cmdHandlerInvokers = cmdHandlerInvokers orElse invokerPF.asInstanceOf[CommandToInvoker[Command, Event]]
        )
      }
    }

    def asyncHandler[C <: Command: ClassTag, E <: Event](cmdHandler: C => Future[E]): Binding[Aggregate] = {
      import scala.concurrent.ExecutionContext.Implicits.global
      // wrap single event in immutable.Seq
      val handlerWithSeq: (C) => Future[immutable.Seq[E]] = (cmd: C) => cmdHandler(cmd).map(immutable.Seq(_))
      asyncHandler.manyEvents(handlerWithSeq)
    }

    def asyncHandler: ManyEventsBinder[Future] = FutureManyEventsBinder(this)

    case class FutureManyEventsBinder(binding: DefaultBinding[A]) extends ManyEventsBinder[Future] {

      def manyEvents[C <: Command: ClassTag, E <: Event](cmdHandler: (C) => Future[immutable.Seq[E]]): Binding[A] = {

        object CmdExtractor extends ClassTagExtractor[C]

        val invokerPF: CommandToInvoker[C, E] = {
          case CmdExtractor(cmd) => FutureCommandHandlerInvoker(cmdHandler)
        }

        //consInvoker: PartialFunction[C, F[immutable.Seq[E]]] => CommandHandlerInvoker[C, E]
        binding.copy(
          cmdHandlerInvokers = cmdHandlerInvokers orElse invokerPF.asInstanceOf[CommandToInvoker[Command, Event]]
        )
      }
    }

    /**
     * Declares an event listener for the event generated by the previous defined command handler
     *
     * @param eventListener - the event listener function
     * @return a Binding for A
     */
    def listener[E <: Event: ClassTag](eventListener: E => A): Binding[Aggregate] = {

      object EvtExtractor extends ClassTagExtractor[E]

      val eventListenerPF: EventToAggregate[A#Event, A] = {
        case EvtExtractor(evt) => eventListener(evt)
      }
      this.copy(eventListeners = eventListeners orElse eventListenerPF)

    }
  }

  /** extractor to convert a total function into a partial function internally _*/
  abstract class ClassTagExtractor[T: ClassTag] {

    def unapply(obj: T): Option[T] = {
      // need classTag because of erasure as we must be able to find back the original type
      if (obj.getClass == ClassTagImplicits[T].runtimeClass) Some(obj)
      else None
    }
  }
}