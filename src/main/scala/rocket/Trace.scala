// See LICENSE.Berkeley for license details.

package freechips.rocketchip.rocket

import chisel3._
import eventtrace.EventTrace

/** What EventTrace records of Rocket's pipeline, in configs that trace: each instruction's pass from Decode until it
  * retires or is killed, and each fetch packet from the I-cache's answer until the instruction buffer has used it. A
  * replay kills an instruction and everything younger and refetches them, so each pass is its own object.
  */
object RocketTrace {

  /** The stage an instruction occupies. Decode is the instruction buffer's head, which no register holds. */
  object Stage extends ChiselEnum {
    val Decode, Execute, Memory, Writeback = Value
  }

  /** Why Rocket replays an instruction, by the stage that decides it: in Execute, `cache_busy`, `divider_busy`, and
    * `load_use` (an operand from a load that missed); in Memory, `writeback_port` and `fpu_nack`; in Writeback,
    * `cache_miss` (the D-cache missed and asked for the line), `store_hazard` (the access touches bytes that a store
    * the D-cache has accepted has not yet written to it), `cache_nack` (the D-cache refused it for any other reason,
    * such as a dirty line to write back first), `csr_stall`, and `rocc_busy`.
    */
  object ReplayCause extends ChiselEnum {
    val cache_busy, divider_busy, load_use, writeback_port, fpu_nack, cache_miss, store_hazard, cache_nack, csr_stall,
      rocc_busy = Value
  }

  /** Why the instruction in Decode waits as its stall begins: the first term of `ctrl_stalld` that holds. A hazard
    * names the instruction in Execute, Memory, or Writeback whose result it needs; `scoreboard` waits on a
    * long-latency result whose instruction has retired.
    */
  object StallCause extends ChiselEnum {
    val execute_hazard, memory_hazard, writeback_hazard, scoreboard, fpu, cache_busy, divider_busy, fence, other = Value
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

  /** One fetch packet, the aligned bytes one I-cache access returns, from the I-cache's answer in S2 until the
    * instruction buffer has used it or a redirect kills it. `pc` is the address requested, which ends in 2 after a
    * jump into the packet's second half. A request killed in S1 returned nothing and is not traced.
    */
  class Fetch(pcBits: Int) extends EventTrace.ObjectType("fetch") {
    val pc    = const(UInt(pcBits.W))
    val stage = mutable(FetchStage())
  }
}
