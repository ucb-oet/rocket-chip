// See LICENSE.Berkeley for license details.

package freechips.rocketchip.rocket

import chisel3._
import org.chipsalliance.cde.config.Field
import eventtrace.{Collection, EventTrace}

/** What EventTrace records of Rocket's pipeline, in configs that trace: each instruction's pass from Decode until it
  * retires or is killed, and each fetch packet from the I-cache's answer until the instruction buffer has used it. A
  * replay kills an instruction and everything younger and refetches them, so each pass is its own object.
  */
object RocketTrace {

  /** The stage an instruction occupies. Decode is the instruction buffer's head, which no register holds. Check holds
    * a vector instruction that passed Writeback without retiring, because the vector unit took it to check for traps
    * element by element, as Saturn's iterative fault checker does; the vector unit then has Rocket retire it or trap.
    */
  object Stage extends ChiselEnum {
    val Decode, Execute, Memory, Writeback, Check = Value
  }

  /** Why Rocket replays an instruction, by the stage that decides it: in Execute, `cache_busy`, `divider_busy`,
    * `checker_busy` (the vector unit, checking an older vector instruction for traps, cannot take a vector
    * instruction), and `load_use` (an operand from a load that missed); in Memory, `writeback_port`, `fpu_nack`,
    * `vector_memory` (a scalar access may not pass an older vector memory instruction: the one in Writeback, or one
    * in the vector unit whose bytes it overlaps), and `checker_busy` (the vector unit is checking an older vector
    * instruction for traps, or takes one now); in Writeback, `cache_miss` (the D-cache missed and asked for the line),
    * `store_hazard` (the access touches bytes that a store the D-cache has accepted has not yet written to it),
    * `cache_nack` (the D-cache refused it for any other reason, such as a dirty line to write back first),
    * `csr_stall`, `rocc_busy`, and the vector unit's reasons, which `VectorReplayCause` describes: `checker_busy`,
    * `age_tags`, `dispatch_queue`, `memory_queue`, `store_pending`, `vector_tlb_miss`, `page_crossing`, and `vector`
    * for another reason.
    */
  object ReplayCause extends ChiselEnum {
    val cache_busy, divider_busy, checker_busy, load_use, writeback_port, fpu_nack, vector_memory, cache_miss,
      store_hazard, cache_nack, csr_stall, rocc_busy, age_tags, dispatch_queue, memory_queue, store_pending,
      vector_tlb_miss, page_crossing, vector = Value
  }

  /** Why the instruction in Decode waits as its stall begins: the first term of `ctrl_stalld` that holds. A hazard
    * names the instruction in Execute, Memory, or Writeback whose result it needs; `scoreboard` waits on a
    * long-latency result whose instruction has retired; `vconfig` is a vector instruction waiting for a vset ahead
    * of it to set vtype and vl in Writeback, and names the youngest such vset; `vector_busy` is a vector CSR access
    * waiting for the vector unit to finish its work.
    */
  object StallCause extends ChiselEnum {
    val execute_hazard, memory_hazard, writeback_hazard, scoreboard, vconfig, fpu, vector_busy, cache_busy,
      divider_busy, fence, other = Value
  }

  /** One pass of a dynamic instruction through the pipeline. `bits` are as the commit log prints them, with a
    * compressed instruction in the low half. `fetch` is the packet holding its last half, whose arrival let it decode:
    * its only packet, or the second of two.
    */
  class Instruction(pcBits: Int) extends EventTrace.ObjectType("instruction") {
    val pc    = const(UInt(pcBits.W))
    val bits  = const(UInt(32.W))
    val fetch = const(Id(new Fetch(pcBits)))
    val stage = mutable(Stage())
  }

  /** Where a fetch packet is. S1 is the cycle its request is looked up in, in which it is created, so no event sets
    * it; a view draws that cycle in it. S2 has the I-cache's answer; a packet S2 could not enqueue is in Miss while
    * Rocket asks for it again; Queue is the fetch queue; Buffer is the instruction buffer holding a half the packet
    * left over, a compressed instruction or the first half of one that ends in the next packet.
    */
  object FetchStage extends ChiselEnum {
    val S1, S2, Miss, Queue, Buffer = Value
  }

  /** Why S2 could not enqueue a packet: the fetch queue was full, the TLB missed, the I-cache missed and asked for the
    * packet's line (`cache_miss`), or else the I-cache did not answer without asking for it (`cache_busy`), as when it
    * is already refilling another line.
    */
  object MissCause extends ChiselEnum {
    val cache_miss, cache_busy, tlb, queue_full = Value
  }

  /** Why S2 hands on a packet marked for replay, which holds no instructions: the core carries the mark to Writeback,
    * which fetches the packet's PC again, not speculatively. `speculative_miss`: the I-cache missed on a speculative
    * fetch it may not refill for, as Rocket's chicken bit `disableSpeculativeICacheRefill` forbids by default or an
    * uncacheable address does; `speculative_tlb_miss`: the TLB missed on a speculative fetch without recent progress;
    * `cache_error`: the I-cache found a parity or ECC error; `btb_mid_instruction`: the BTB predicted a taken branch in
    * the middle of an instruction.
    */
  object FetchReplayCause extends ChiselEnum {
    val speculative_miss, speculative_tlb_miss, cache_error, btb_mid_instruction = Value
  }

  /** Why the core redirected fetch, killing every packet in flight. From Writeback, in Rocket's order: `interrupt`,
    * `trap` (an exception, including an ecall or ebreak the CSR file raises), `eret`, `replay` (an instruction Rocket
    * replays, or a packet the frontend marked for replay), and `flush` (an instruction that flushes the pipeline, such
    * as a CSR write or fence.i). From Memory: `sfence`; `mispredict`, where the next instruction behind is not at the
    * PC this one goes to; and `unconfirmed`, where neither Execute nor the instruction buffer holds a next instruction
    * to check, so Rocket redirects without knowing fetch to be wrong.
    */
  object KillCause extends ChiselEnum {
    val interrupt, trap, eret, replay, flush, sfence, mispredict, unconfirmed = Value
  }

  /** One fetch packet, the aligned bytes one I-cache access returns, from the I-cache's answer in S2 until the
    * instruction buffer has used it or a redirect kills it. `pc` is the address requested, which ends in 2 after a
    * jump into the packet's second half. A request killed in S1 returned nothing and is not traced.
    */
  class Fetch(pcBits: Int) extends EventTrace.ObjectType("fetch") {
    val pc    = const(UInt(pcBits.W))
    val stage = mutable(FetchStage())
  }
}

/** EventTrace: Rocket's instructions, which the frontend declares and the tile hands to its vector unit, so that the
  * vector unit's events can name the instruction each of its operations comes from. The vector unit calls it while it
  * elaborates, after the frontend has.
  */
case object RocketTraceInstructions extends Field[() => Collection[RocketTrace.Instruction]]
