/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.coroutines

import org.jetbrains.kotlin.codegen.inline.insnText
import org.jetbrains.kotlin.codegen.optimization.common.*
import org.jetbrains.kotlin.codegen.optimization.fixStack.peek
import org.jetbrains.kotlin.codegen.optimization.fixStack.peekWords
import org.jetbrains.kotlin.codegen.optimization.fixStack.top
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.tree.analysis.BasicValue
import org.jetbrains.org.objectweb.asm.tree.analysis.Frame
import org.jetbrains.org.objectweb.asm.tree.analysis.Interpreter

/**
 * In cases like:
 * NEW
 * DUP
 * LDC "First"
 * ASTORE 1
 * ASTORE 2
 * ASTORE 3
 * INVOKE suspensionPoint
 * ALOAD 3
 * ALOAD 2
 * ALOAD 1
 * LDC "Second"
 * INVOKESPECIAL <init>(String;String)

 * Replace store/load instruction with moving NEW/DUP after suspension point:
 * LDC "First"
 * ASTORE 1
 * INVOKE suspensionPoint
 * ALOAD 1
 * LDC "Second"
 * ASTORE 5
 * ASTORE 4
 * NEW
 * DUP
 * ALOAD 4
 * ASTORE 5
 * INVOKESPECIAL <init>(String)
 *
 * This is needed because later we spill this variables containing uninitialized object into fields -> leads to VerifyError
 * Note that this transformation changes semantics a bit (class <clinit> may be invoked by NEW instruction)
 * TODO: current implementation affects all store/loads of uninitialized objects, even valid ones:
 * MyClass(try { 1 } catch (e: Exception) { 0 }) // here uninitialized MyClass-object is being spilled before try-catch and then loaded
 *
 * How this works:
 * 1. For each invokespecial <init> determine if NEW uninitialized value was saved to local at least once
 * 2. If it wasn't then do nothing
 * 3. If it was then:
 *   - remove all relevant NEW/DUP/LOAD/STORE instructions
 *   - spill rest of constructor arguments to new local vars
 *   - generate NEW/DUP
 *   - restore constructor arguments
 */
class UninitializedStoresProcessor(
        private val methodNode: MethodNode,
        private val shouldForceClassInit: Boolean
) {
    // <init> method is "special", because it will invoke <init> from this class or from a base class for #0
    //
    // <clinit> method is "special", because <clinit> for singleton objects is generated as:
    //      NEW <obj>
    //      INVOKESPECIAL <obj>.<init> ()V
    // and the newly created value is dropped.
    private val isInSpecialMethod = methodNode.name == "<init>" || methodNode.name == "<clinit>"

    fun run() {
        val interpreter = UninitializedNewValueMarkerInterpreter(methodNode.instructions)

        val frames = CustomFramesMethodAnalyzer(
                "fake", methodNode, interpreter,
                this::UninitializedNewValueFrame
        ).analyze()

        interpreter.analyzePopInstructions(frames)

        for ((index, insn) in methodNode.instructions.toArray().withIndex()) {
            val frame = frames[index] ?: continue
            val uninitializedValue = frame.getUninitializedValueForConstructorCall(insn) ?: continue

            val newInsn = uninitializedValue.newInsn
            val removableUsages: Set<AbstractInsnNode> = interpreter.uninitializedValuesToRemovableUsages[newInsn]!!
            assert(removableUsages.isNotEmpty()) { "At least DUP copy operation expected" }

            // Value generated by NEW wasn't store to local/field (only DUPed)
            if (removableUsages.size == 1) continue

            methodNode.instructions.run {
                removeAll(removableUsages)

                if (shouldForceClassInit) {
                    // Replace 'NEW C' instruction with "manual" initialization of class 'C':
                    //      LDC [typeName for C]
                    //      INVOKESTATIC java/lang/Class.forName (Ljava/lang/String;)Ljava/lang/Class;
                    //      POP
                    val typeNameForClass = newInsn.desc.replace('/', '.')
                    insertBefore(newInsn, LdcInsnNode(typeNameForClass))
                    insertBefore(newInsn, MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false
                    ))
                    set(newInsn, InsnNode(Opcodes.POP))
                }
                else {
                    remove(newInsn)
                }
            }

            val indexOfConstructorArgumentFromTopOfStack = Type.getArgumentTypes((insn as MethodInsnNode).desc).size
            val storedTypes = arrayListOf<Type>()
            var nextVarIndex = methodNode.maxLocals

            for (i in 0 until indexOfConstructorArgumentFromTopOfStack) {
                val value = frame.getStack(frame.stackSize - 1 - i)
                val type = value.type
                methodNode.instructions.insertBefore(insn, VarInsnNode(type.getOpcode(Opcodes.ISTORE), nextVarIndex))
                nextVarIndex += type.size
                storedTypes.add(type)
            }
            methodNode.maxLocals = Math.max(methodNode.maxLocals, nextVarIndex)

            methodNode.instructions.insertBefore(insn, insnListOf(
                    TypeInsnNode(Opcodes.NEW, newInsn.desc),
                    InsnNode(Opcodes.DUP)
            ))

            for (type in storedTypes.reversed()) {
                nextVarIndex -= type.size
                methodNode.instructions.insertBefore(insn, VarInsnNode(type.getOpcode(Opcodes.ILOAD), nextVarIndex))
            }
        }
    }

    private inner class UninitializedNewValueFrame(nLocals: Int, nStack: Int) : Frame<BasicValue>(nLocals, nStack) {
        override fun execute(insn: AbstractInsnNode, interpreter: Interpreter<BasicValue>?) {
            val replaceTopValueWithInitialized = getUninitializedValueForConstructorCall(insn) != null

            super.execute(insn, interpreter)

            if (replaceTopValueWithInitialized) {
                // Drop top value
                val value = pop() as UninitializedNewValue

                // uninitialized value become initialized after <init> call
                push(StrictBasicValue(value.type))
            }
        }
    }

    /**
     * @return value generated by NEW that used as 0-th argument of constructor call or null if current instruction is not constructor call
     */
    private fun Frame<BasicValue>.getUninitializedValueForConstructorCall(insn: AbstractInsnNode): UninitializedNewValue? {
        if (!insn.isConstructorCall()) return null

        assert(insn.opcode == Opcodes.INVOKESPECIAL) { "Expected opcode Opcodes.INVOKESPECIAL for <init>, but ${insn.opcode} found" }
        val paramsCountIncludingReceiver = Type.getArgumentTypes((insn as MethodInsnNode).desc).size + 1
        val newValue = peek(paramsCountIncludingReceiver) as? UninitializedNewValue ?:
                       if (isInSpecialMethod)
                           return null
                       else
                           error("Expected value generated with NEW")

        assert(peek(paramsCountIncludingReceiver - 1) is UninitializedNewValue) {
            "Next value after NEW should be one generated by DUP"
        }

        return newValue
    }

    private class UninitializedNewValue(
            val newInsn: TypeInsnNode,
            val internalName: String
    ) : StrictBasicValue(Type.getObjectType(internalName)) {
        override fun toString() = "UninitializedNewValue(internalName='$internalName')"
    }


    private fun AbstractInsnNode.isConstructorCall() = this is MethodInsnNode && this.name == "<init>"

    private class UninitializedNewValueMarkerInterpreter(private val instructions: InsnList) : OptimizationBasicInterpreter() {
        val uninitializedValuesToRemovableUsages = hashMapOf<AbstractInsnNode, MutableSet<AbstractInsnNode>>()
        override fun newOperation(insn: AbstractInsnNode): BasicValue? {
            if (insn.opcode == Opcodes.NEW) {
                uninitializedValuesToRemovableUsages.getOrPut(insn) { mutableSetOf() }
                return UninitializedNewValue(insn as TypeInsnNode, insn.desc)
            }
            return super.newOperation(insn)
        }

        override fun copyOperation(insn: AbstractInsnNode, value: BasicValue?): BasicValue? {
            if (value is UninitializedNewValue) {
                checkUninitializedObjectCopy(value.newInsn, insn)
                uninitializedValuesToRemovableUsages[value.newInsn]!!.add(insn)
                return value
            }
            return super.copyOperation(insn, value)
        }

        override fun merge(v: BasicValue, w: BasicValue): BasicValue {
            if (v === w) return v
            if (v === StrictBasicValue.UNINITIALIZED_VALUE || w === StrictBasicValue.UNINITIALIZED_VALUE) {
                return StrictBasicValue.UNINITIALIZED_VALUE
            }

            if (v is UninitializedNewValue || w is UninitializedNewValue) {
                if ((v as? UninitializedNewValue)?.newInsn !== (w as? UninitializedNewValue)?.newInsn) {
                    // Merge of two different ANEW result is possible, but such values should not be used further
                    return StrictBasicValue.UNINITIALIZED_VALUE
                }

                return v
            }

            return super.merge(v, w)
        }

        private fun checkUninitializedObjectCopy(newInsn: TypeInsnNode, usageInsn: AbstractInsnNode) {
            when (usageInsn.opcode) {
                Opcodes.DUP, Opcodes.ASTORE, Opcodes.ALOAD -> {}
                else -> error("Unexpected copy instruction for ${newInsn.debugText}: ${usageInsn.debugText}")
            }
        }

        private val AbstractInsnNode.debugText
            get() = "${instructions.indexOf(this)}: $insnText"

        fun analyzePopInstructions(frames: Array<Frame<BasicValue>?>) {
            val insns = instructions.toArray()
            for (i in frames.indices) {
                val frame = frames[i] ?: continue
                val insn = insns[i]
                when (insn.opcode) {
                    Opcodes.POP -> analyzePop(insn, frame)
                    Opcodes.POP2 -> analyzePop2(insn, frame)
                }
            }
        }

        private fun analyzePop(insn: AbstractInsnNode, frame: Frame<BasicValue>) {
            val top = frame.top() ?: error("Stack underflow on POP: ${insn.debugText}")
            if (top is UninitializedNewValue) {
                uninitializedValuesToRemovableUsages[top.newInsn]!!.add(insn)
            }
        }

        private fun analyzePop2(insn: AbstractInsnNode, frame: Frame<BasicValue>) {
            val top2 = frame.peekWords(2) ?: error("Stack underflow on POP2: ${insn.debugText}")
            for (value in top2) {
                if (value is UninitializedNewValue) {
                    error("Unexpected POP2 instruction for ${value.newInsn.debugText}: ${insn.debugText}")
                }
            }
        }
    }
}
