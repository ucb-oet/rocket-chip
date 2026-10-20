// See LICENSE.Berkeley for license details.

package freechips.rocketchip.rocket

import chisel3._
import chisel3.util.PriorityMux
import org.chipsalliance.cde.config.Field
import eventtrace.{Collection, EventTrace}

/** What EventTrace records of Rocket, in configs that trace: each instruction's pass through the pipeline, and each
  * fetch packet. A replay refetches, so each pass is its own object.
  */
object RocketTrace {

  /** Where an instruction is. */
  object Stage extends ChiselEnum {
    val Decode    = Value // the instruction buffer's head, which no register holds
    val Execute   = Value
    val Memory    = Value
    val Writeback = Value
    val Check     = Value // past Writeback: the vector unit checks it for traps, then has Rocket retire it or trap
  }

  /** Why Rocket replays an instruction, by the stage that decides it. */
  object ReplayCause extends ChiselEnum {
    // Execute
    val cache_busy      = Value // a memory operation finds the D-cache not ready
    val divider_busy    = Value
    val checker_busy    = Value // the vector unit is checking an older instruction for traps; also Memory, Writeback
    val load_use        = Value // an operand comes from a load that missed
    // Memory
    val writeback_port  = Value // the D-cache needs the writeback port
    val fpu_nack        = Value
    val vector_memory   = Value // a scalar access may not pass an older vector memory instruction
    // Writeback
    val cache_miss      = Value // the D-cache missed and asked for the line
    val store_hazard    = Value // the access touches bytes that an accepted store has not yet written
    val cache_nack      = Value // the D-cache refused it for another reason, such as a dirty line to write back
    val csr_stall       = Value
    val rocc_busy       = Value
    // Writeback, the vector unit's reasons
    val age_tags        = Value // no free age tag
    val dispatch_queue  = Value // its dispatch queue is full
    val memory_queue    = Value // its load or store instruction queue is full
    val store_pending   = Value // a vector memory instruction waits for the D-cache to write the scalar stores
    val vector_tlb_miss = Value
    val page_crossing   = Value // the access continues into the next page, which the replay checks
    val vector          = Value // another reason
  }

  /** The vector unit's reason for a replay, as a `ReplayCause`. */
  def replayCause(c: VectorReplayCause.Type): ReplayCause.Type = PriorityMux(Seq(
    (c === VectorReplayCause.checker_busy)   -> ReplayCause.checker_busy,
    (c === VectorReplayCause.age_tags)       -> ReplayCause.age_tags,
    (c === VectorReplayCause.dispatch_queue) -> ReplayCause.dispatch_queue,
    (c === VectorReplayCause.memory_queue)   -> ReplayCause.memory_queue,
    (c === VectorReplayCause.store_pending)  -> ReplayCause.store_pending,
    (c === VectorReplayCause.tlb_miss)       -> ReplayCause.vector_tlb_miss,
    (c === VectorReplayCause.page_crossing)  -> ReplayCause.page_crossing,
    true.B                                   -> ReplayCause.vector))

  /** Why the instruction in Decode stalls: the first term of `ctrl_stalld` that holds as the stall begins. */
  object StallCause extends ChiselEnum {
    val execute_hazard   = Value // needs the result of the instruction in Execute, the blocker
    val memory_hazard    = Value // in Memory
    val writeback_hazard = Value // in Writeback
    val scoreboard       = Value // waits for a long-latency result whose instruction has retired
    val vconfig          = Value // a vector instruction waits for a vset ahead of it, the blocker
    val fpu              = Value
    val vector_busy      = Value // a vector CSR access waits for the vector unit
    val cache_busy       = Value
    val divider_busy     = Value
    val fence            = Value
    val other            = Value // single step, clock gating, or a CSR stall such as WFI
  }

  /** One pass of an instruction through the pipeline. */
  class Instruction(pcBits: Int) extends EventTrace.ObjectType("instruction") {
    val pc    = const(UInt(pcBits.W))
    val bits  = const(UInt(32.W))             // as the commit log prints them; a compressed one in the low half
    val fetch = parent(new Fetch(pcBits))     // the packet holding its last half
    val stage = mutable(Stage())
  }

  /** Where a fetch packet is. */
  object FetchStage extends ChiselEnum {
    val S1     = Value // looked up; the cycle it is created in, which no event sets
    val S2     = Value // has the I-cache's answer
    val Miss   = Value // S2 could not enqueue it, and Rocket asks for it again
    val Queue  = Value // in the fetch queue
    val Buffer = Value // in the instruction buffer, which holds a half of it
  }

  /** Why S2 could not enqueue a packet. */
  object MissCause extends ChiselEnum {
    val cache_miss = Value // the I-cache missed and asked for the line
    val cache_busy = Value // the I-cache did not answer, without asking, as while it refills another line
    val tlb        = Value
    val queue_full = Value
  }

  /** Why S2 hands on a packet marked for replay, which Writeback fetches again. */
  object FetchReplayCause extends ChiselEnum {
    val speculative_miss     = Value // the I-cache missed on a speculative fetch it may not refill for
    val speculative_tlb_miss = Value
    val cache_error          = Value // a parity or ECC error
    val btb_mid_instruction  = Value // the BTB predicted a taken branch in the middle of an instruction
  }

  /** Why the core redirected fetch, killing every packet in flight. */
  object KillCause extends ChiselEnum {
    // From Writeback, in Rocket's order
    val interrupt   = Value
    val trap        = Value // an exception, including an ecall or ebreak
    val eret        = Value
    val replay      = Value // an instruction's replay, or a packet marked for replay
    val flush       = Value // an instruction that flushes the pipeline, such as a CSR write or fence.i
    // From Memory
    val sfence      = Value
    val mispredict  = Value // the next instruction is not at the PC this one goes to
    val unconfirmed = Value // no next instruction to check, so Rocket redirects without knowing fetch was wrong
  }

  /** One fetch packet, the aligned bytes of one I-cache access, from S2 until the instruction buffer uses it or a
    * redirect kills it.
    */
  class Fetch(pcBits: Int) extends EventTrace.ObjectType("fetch") {
    val pc    = const(UInt(pcBits.W))  // the address requested; ends in 2 after a jump into the second half
    val stage = mutable(FetchStage())
  }
}

/** EventTrace: Rocket's instructions, which the tile binds for the vector unit. */
case object RocketTraceInstructions extends Field[Option[Collection[RocketTrace.Instruction]]](None)

/** EventTrace: the frontend's fetch packets, which the tile binds for the core's instruction buffer. */
case object RocketTracePackets extends Field[Option[Collection[RocketTrace.Fetch]]](None)
