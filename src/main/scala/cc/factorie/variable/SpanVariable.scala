/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.variable

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable,DoubleLinkedList}
import scala.reflect.Manifest
import scala.util.Random
import scala.util.Sorting
import cc.factorie.variable._

/** An immutable value indicating a subsequence of a Chain (and whether this span is to be considered present or "active" now). */
case class Span[C<:Chain[C,E],E<:ChainLink[E,C]](chain:C, start:Int, length:Int, present:Boolean = true) extends IndexedSeq[E] {
  def apply(i:Int) = chain.links(start + i)
  def end: Int = start + length
  override def head: E = apply(0)
  def isAtStart: Boolean = start == 0
  def isAtEnd: Boolean = start + length == chain.length
  def hasSuccessor(i: Int) = (start + length - 1 + i) < chain.length
  def hasPredecessor(i: Int) = (start - i) >= 0
  def successor(i: Int): E = if (hasSuccessor(i)) chain(start + length - 1 + i) else null.asInstanceOf[E]
  def predecessor(i: Int): E = if (hasPredecessor(i)) chain(start - i) else null.asInstanceOf[E]
  /** Given a span within the same chain as this one, return true if the two spans overlap by at least one element. */
  def overlaps(that: Span[C,E]): Boolean = {
    assert(this.chain eq that.chain)
    (that.start <= this.start && that.end-1 >= this.start) ||
    (this.start <= that.start && this.end-1 >= that.start)
  }
  /** Return a sequence of n elements before the beginning of this span.  May return a sequence of length less than n if there are insufficient elements. */
  def prevWindow(n:Int): Seq[E] = for (i <- math.max(0,start-n) until start) yield chain(i)
  /** Return a sequence of n elements after the last element of this span.  May return a sequence of length less than n if there are insufficient elements. */
  def nextWindow(n:Int): Seq[E] = for (i <- end until math.min(chain.length,end+n)) yield chain(i)
  def window(n:Int): Seq[E] = for (i <- math.max(0,start-n) until math.min(chain.length,end+n)) yield chain(i)
  def windowWithoutSelf(n:Int): Seq[E] = for (i <- math.max(0,start-n) until math.min(chain.length,end+n); if i < start || i > end) yield chain(i)
  // Support for next/prev of elements within a span
  @inline private def requireInSpan(elt:E): Unit = { require(elt.chain eq chain, "Element not in chain."); require(elt.position >= start && elt.position < end, "Element outside span.") }
  /** Given an elt in the Span, return true if the span contains an additional element after elt. */
  def hasNext(elt:E): Boolean = { requireInSpan(elt); elt.position+1 < end }
  /** Given an elt in the Span, return true if the span contains an additional element before elt. */
  def hasPrev(elt:E): Boolean = { requireInSpan(elt); elt.position > start }
  def next(elt:E): E = if (hasNext(elt)) elt.next else null.asInstanceOf[E]
  def prev(elt:E): E = if (hasPrev(elt)) elt.prev else null.asInstanceOf[E]
}

/** A (non-mutable) variable whose value is a Span. */
trait SpanVar[C<:Chain[C,E],E<:ChainLink[E,C]] extends IndexedSeqVar[E] {
  type Value = Span[C,E]
  /** If true then Diff objects will return this as their variable even when the value indicates it is not "present". */
  def diffIfNotPresent = false
  // methods "length" and "apply" are defined in IndexedSeqVar.
  // Define a few forwarding method for convenience
  def chain: C = value.chain
  def start: Int = value.start
  def end: Int = value.end
  override def head: E = value.apply(0)
  def hasPredecessor(i: Int) = (start - i) >= 0
}

/** A mutable variable whose value is a Span. */
class MutableSpanVar[C<:Chain[C,E],E<:ChainLink[E,C]](span:Span[C,E]) extends SpanVar[C,E] with MutableVar {
  private var _value: Span[C,E] = span
  def value: Span[C,E] = _value
  /** If true, this SpanVariable will be scored by a difflist, even if it is in its deleted non-"present" state. */
  def preChange(implicit d:DiffList): Unit = {}
  def postChange(implicit d:DiffList): Unit = {}
  def set(newValue:Value)(implicit d:DiffList): Unit = { preChange; new Set(newValue); postChange }
  //def removeFromList(list:SpanList[SpanVar[C,E],C,E])(implicit d: DiffList): Unit = { preChange; list.remove(this)(d);  postChange }
  def setStart(s: Int)(implicit d: DiffList): Unit = if (s != start) { preChange; new SetStart(s); postChange }
  def setLength(l: Int)(implicit d: DiffList): Unit = if (l != length) { preChange; new SetLength(l); postChange }
  def trimStart(n: Int)(implicit d: DiffList): Unit = if (n > 0) { preChange; new TrimStart(n); postChange }
  def trimEnd(n: Int)(implicit d: DiffList): Unit = if (n > 0) { preChange; new TrimEnd(n); postChange }
  def prepend(n: Int)(implicit d: DiffList): Unit = if (n > 0) { preChange; new Prepend(n); postChange }
  def append(n: Int)(implicit d: DiffList): Unit = if (n > 0) { preChange; new Append(n); postChange }
  def canPrepend(n: Int) = _value.start >= n
  def canAppend(n: Int) = _value.start + _value.length + n <= _value.chain.length
  trait MutableSpanDiff extends Diff {
    def newValue: Span[C,E] // Must be defined as a lazy val so that it will be initialized in time for the AutoDiff usage
    val oldValue: Span[C,E] = _value
    def variable = if (value.present || diffIfNotPresent) MutableSpanVar.this else null
    def redo() = _value = newValue
    def undo() = _value = oldValue
  }
  case class Set(newSpan:Span[C,E])(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = newSpan
    override def toString = "Set("+newSpan+","+MutableSpanVar.this+")"    
  }
  case class SetStart(newStart: Int)(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = new Span[C,E](_value.chain, newStart, _value.length, _value.present)
    override def toString = "SetStart("+newStart+","+MutableSpanVar.this+")"    
  }
  case class SetLength(newLength: Int)(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = new Span[C,E](_value.chain, _value.start, newLength, _value.present)
    override def toString = "SetLength("+newLength+","+MutableSpanVar.this+")"    
  }
  case class TrimStart(n: Int)(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = new Span[C,E](_value.chain, _value.start+n, _value.length-n, _value.present)
    override def toString = "TrimStart("+n+","+MutableSpanVar.this+")"
  }
  case class TrimEnd(n: Int)(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = new Span[C,E](_value.chain, _value.start, _value.length-n, _value.present)
    override def toString = "TrimEnd("+n+","+MutableSpanVar.this+")"
  }
  case class Prepend(n: Int)(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = new Span[C,E](_value.chain, _value.start-n, _value.length+n, _value.present)
    override def toString = "Prepend("+n+","+MutableSpanVar.this+")"
  }
  case class Append(n: Int)(implicit d: DiffList) extends AutoDiff with MutableSpanDiff {
    lazy val newValue = new Span[C,E](_value.chain, _value.start, _value.length+n, _value.present)
    override def toString = "Append("+n+","+MutableSpanVar.this+")"
  }  
}

class SpanVariable[C<:Chain[C,E],E<:ChainLink[E,C]](span:Span[C,E]) extends MutableSpanVar[C,E](span) {
  def this(chain:C, start:Int, length:Int) = this(new Span[C,E](chain, start, length))
}


/** A collection of Spans, with various methods for retrieving subsets. */
class SpanList[S<:Span[C,E],C<:Chain[C,E],E<:ChainLink[E,C]] extends ArrayBuffer[S] {
  def spansOfClass[A<:S](c:Class[A]): Seq[A] = this.filter(s => c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClass[A<:S](implicit m:Manifest[A]): Seq[A] = spansOfClass[A](m.runtimeClass.asInstanceOf[Class[A]])
  // Spans sorted by their start position
  def orderedSpans: Seq[S] = this.toList.sortWith((s1,s2) => s1.start < s2.start) // TODO Make this more efficient by avoiding toList
  def orderedSpansOfClass[A<:S](c:Class[A]): Seq[A] = spansOfClass(c).toList.sortWith((s1,s2) => s1.start < s2.start) // TODO Make this more efficient by avoiding toList
  def orderedSpansOfClass[A<:S](implicit m:Manifest[A]): Seq[A] = orderedSpansOfClass(m.runtimeClass.asInstanceOf[Class[A]])
  // Spans in relation to a ChainLink element
  def spansContaining(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length)
  def hasSpansContaining(e:E): Boolean = this.exists(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length)
  def spansStartingAt(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start == e.position)
  def spansEndingAt(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 == e.position)
  def spansFollowing(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start > e.position)
  def spansPreceeding(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 < e.position)

  def spansOfClassContaining[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def hasSpansOfClassContaining[A<:S](c:Class[A], e:E): Boolean = this.exists(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length && c.isAssignableFrom(s.getClass))
  def spansOfClassStartingAt[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start == e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassEndingAt[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 == e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassFollowing[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start > e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassPreceeding[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 < e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]

  def spansOfClassContaining[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassContaining[A](m.runtimeClass.asInstanceOf[Class[A]], e)
  def hasSpansOfClassContaining[A<:S](e:E)(implicit m:Manifest[A]): Boolean = hasSpansOfClassContaining(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassStartingAt[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassStartingAt(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassEndingAt[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassEndingAt(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassFollowing[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassFollowing(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassPreceeding[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassPreceeding(m.runtimeClass.asInstanceOf[Class[A]], e)
}

/** A collection of SpanVars, with various methods for retrieving subsets, and tracking additions and removals via DiffLists. */
class SpanVarList[S<:SpanVar[C,E],C<:Chain[C,E],E<:ChainLink[E,C]] extends ArrayBuffer[S] {
  /** Add the span to the list of spans.  Unlike +=, make a DiffList entry for the change. */
  def add(s:S)(implicit d:DiffList): Unit = {
    if (d ne null) d += AddSpanListDiff(s)
    +=(s)
  }
  /** Remove the span from the list of spans.  Unlike -=, make a DiffList entry for the change. */
  def remove(s:S)(implicit d:DiffList): Unit = {
    if (d ne null) d += RemoveSpanListDiff(s)
    -=(s)
  }
  
  trait SpanListDiff extends Diff { def list = SpanVarList.this }
  case class AddSpanListDiff(span:S) extends SpanListDiff {
    // Cannot be an AutoDiff, because of initialization ordering 'done' will end up false
    var done = true
    def variable: S = if (span.value.present || span.diffIfNotPresent) span else null.asInstanceOf[S]
    def redo() = { SpanVarList.this.+=(span); assert(!done); done = true }
    def undo() = { SpanVarList.this.-=(span); assert(done); done = false }
    override def toString = "AddSpanVariable("+span+")"
  }
  case class RemoveSpanListDiff(span:S) extends SpanListDiff {
    // Cannot be an AutoDiff, because of initialization ordering 'done' will end up false
    var done = true
    def variable: S = if (span.value.present || span.diffIfNotPresent) span else null.asInstanceOf[S]
    def redo() = { SpanVarList.this.-=(span); assert(!done); done = true }
    def undo() = { SpanVarList.this.+=(span); assert(done); done = false }
    override def toString = "RemoveSpanVariable("+span+")"
  }
  
  def spansOfClass[A<:S](c:Class[A]): Seq[A] = this.filter(s => c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClass[A<:S](implicit m:Manifest[A]): Seq[A] = spansOfClass[A](m.runtimeClass.asInstanceOf[Class[A]])
  // Spans sorted by their start position
  def orderedSpans: Seq[S] = this.toList.sortWith((s1,s2) => s1.start < s2.start) // TODO Make this more efficient by avoiding toList
  def orderedSpansOfClass[A<:S](c:Class[A]): Seq[A] = spansOfClass(c).toList.sortWith((s1,s2) => s1.start < s2.start) // TODO Make this more efficient by avoiding toList
  def orderedSpansOfClass[A<:S](implicit m:Manifest[A]): Seq[A] = orderedSpansOfClass(m.runtimeClass.asInstanceOf[Class[A]])
  // Spans in relation to a ChainLink element
  def spansContaining(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length)
  def hasSpansContaining(e:E): Boolean = this.exists(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length)
  def spansStartingAt(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start == e.position)
  def spansEndingAt(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 == e.position)
  def spansFollowing(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start > e.position)
  def spansPreceeding(e:E): Seq[S] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 < e.position)

  def spansOfClassContaining[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def hasSpansOfClassContaining[A<:S](c:Class[A], e:E): Boolean = this.exists(s => (s.chain eq e.chain) && s.start <= e.position && e.position < s.start + s.length && c.isAssignableFrom(s.getClass))
  def spansOfClassStartingAt[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start == e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassEndingAt[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 == e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassFollowing[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start > e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]
  def spansOfClassPreceeding[A<:S](c:Class[A], e:E): Seq[A] = this.filter(s => (s.chain eq e.chain) && s.start + s.length - 1 < e.position && c.isAssignableFrom(s.getClass)).asInstanceOf[Seq[A]]

  def spansOfClassContaining[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassContaining[A](m.runtimeClass.asInstanceOf[Class[A]], e)
  def hasSpansOfClassContaining[A<:S](e:E)(implicit m:Manifest[A]): Boolean = hasSpansOfClassContaining(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassStartingAt[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassStartingAt(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassEndingAt[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassEndingAt(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassFollowing[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassFollowing(m.runtimeClass.asInstanceOf[Class[A]], e)
  def spansOfClassPreceeding[A<:S](e:E)(implicit m:Manifest[A]): Seq[A] = spansOfClassPreceeding(m.runtimeClass.asInstanceOf[Class[A]], e)
}
