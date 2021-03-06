/**
*  Copyright (c) 2010, Aemon Cannon
*  All rights reserved.
*  
*  Redistribution and use in source and binary forms, with or without
*  modification, are permitted provided that the following conditions are met:
*      * Redistributions of source code must retain the above copyright
*        notice, this list of conditions and the following disclaimer.
*      * Redistributions in binary form must reproduce the above copyright
*        notice, this list of conditions and the following disclaimer in the
*        documentation and/or other materials provided with the distribution.
*      * Neither the name of ENSIME nor the
*        names of its contributors may be used to endorse or promote products
*        derived from this software without specific prior written permission.
*  
*  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
*  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
*  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
*  DISCLAIMED. IN NO EVENT SHALL Aemon Cannon BE LIABLE FOR ANY
*  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
*  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
*  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
*  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
*  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
*  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.ensime.server
import java.io.File
import org.ensime.config.ProjectConfig
import org.ensime.debug.ProjectDebugInfo
import org.ensime.model._
import org.ensime.protocol.ProtocolConst._
import org.ensime.util._
import scala.actors._
import scala.actors.Actor._
import scala.collection.immutable
import scala.tools.nsc.io.AbstractFile
import scala.tools.refactoring.common.Change
import scalariform.astselect.AstSelector
import scalariform.formatter.ScalaFormatter
import scalariform.parser.ScalaParserException
import scalariform.utils.Range


trait RPCTarget { self: Project =>

  import protocol._

  def rpcShutdownServer(callId: Int) {
    sendRPCReturn(toWF(true), callId)
    shutdownServer()
  }

  def rpcInitProject(conf: ProjectConfig, callId: Int) {
    initProject(conf)
    sendRPCReturn(toWF(conf), callId)
  }

  def rpcPeekUndo(callId: Int) {
    peekUndo match {
      case Right(result) => sendRPCReturn(toWF(result), callId)
      case Left(msg) => sendRPCError(ErrPeekUndoFailed, Some(msg), callId)
    }
  }

  def rpcExecUndo(undoId: Int, callId: Int) {
    execUndo(undoId) match {
      case Right(result) => sendRPCReturn(toWF(result), callId)
      case Left(msg) => sendRPCError(ErrExecUndoFailed, Some(msg), callId)
    }
  }

  def rpcReplConfig(callId: Int) {
    sendRPCReturn(toWF(config.replConfig), callId)
  }

  def rpcDebugConfig(callId: Int) {
    debugInfo = Some(new ProjectDebugInfo(config))
    sendRPCReturn(toWF(config.debugConfig), callId)
  }

  def rpcBuilderInit(callId: Int) {
    val b = getOrStartBuilder
    b ! RPCRequestEvent(RebuildAllReq(), callId)
  }

  def rpcBuilderAddFiles(filenames: Iterable[String], callId: Int) {
    val files = filenames.map(s => new File(s.toString))
    val b = getOrStartBuilder
    b ! RPCRequestEvent(AddSourceFilesReq(files), callId)
  }

  def rpcBuilderUpdateFiles(filenames: Iterable[String], callId: Int) {
    val files = filenames.map(s => new File(s.toString))
    val b = getOrStartBuilder
    b ! RPCRequestEvent(UpdateSourceFilesReq(files), callId)
  }

  def rpcBuilderRemoveFiles(filenames: Iterable[String], callId: Int) {
    val files = filenames.map(s => new File(s.toString))
    val b = getOrStartBuilder
    b ! RPCRequestEvent(RemoveSourceFilesReq(files), callId)
  }

  def rpcDebugUnitInfo(sourceName: String, line: Int, packPrefix: String, callId: Int) {
    val info = debugInfo.getOrElse(new ProjectDebugInfo(config))
    debugInfo = Some(info)
    val unit = info.findUnit(sourceName, line, packPrefix)
    unit match {
      case Some(unit) => {
        sendRPCReturn(toWF(unit), callId)
      }
      case None => {
        sendRPCReturn(toWF(false), callId)
      }
    }
  }

  def rpcDebugClassLocsToSourceLocs(pairs: Iterable[(String, Int)], callId: Int) {
    val info = debugInfo.getOrElse(new ProjectDebugInfo(config))
    debugInfo = Some(info)
    sendRPCReturn(
      toWF(info.debugClassLocsToSourceLocs(pairs)),
      callId)
  }

  def rpcSymbolDesignations(f: String, start: Int, end: Int, requestedTypes:List[Symbol], callId: Int) {
    val file: File = new File(f)
    analyzer ! RPCRequestEvent(SymbolDesignationsReq(file, start, end, requestedTypes), callId)
  }

  def rpcTypecheckFile(f: String, callId: Int) {
    val file: File = new File(f)
    analyzer ! RPCRequestEvent(ReloadFileReq(file), callId)
  }

  def rpcRemoveFile(f: String, callId: Int) {
    val file: File = new File(f)
    analyzer ! RPCRequestEvent(RemoveFileReq(file), callId)
    sendRPCAckOK(callId)
  }

  def rpcTypecheckAll(callId: Int) {
    analyzer ! RPCRequestEvent(ReloadAllReq(), callId)
  }

  def rpcScopeCompletion(f: String, point: Int, prefix: String, constructor: Boolean, callId: Int) {
    analyzer ! RPCRequestEvent(ScopeCompletionReq(new File(f), point, prefix, constructor), callId)
  }

  def rpcTypeCompletion(f: String, point: Int, prefix: String, callId: Int) {
    analyzer ! RPCRequestEvent(TypeCompletionReq(new File(f), point, prefix), callId)
  }

  def rpcPackageMemberCompletion(path: String, prefix: String, callId: Int) {
    analyzer ! RPCRequestEvent(PackageMemberCompletionReq(path, prefix), callId)
  }

  def rpcInspectTypeAtPoint(f: String, point: Int, callId: Int) {
    analyzer ! RPCRequestEvent(InspectTypeReq(new File(f), point), callId)
  }

  def rpcInspectTypeById(id: Int, callId: Int) {
    analyzer ! RPCRequestEvent(InspectTypeByIdReq(id), callId)
  }

  def rpcSymbolAtPoint(f: String, point: Int, callId: Int) {
    analyzer ! RPCRequestEvent(SymbolAtPointReq(new File(f), point), callId)
  }

  def rpcTypeById(id: Int, callId: Int) {
    analyzer ! RPCRequestEvent(TypeByIdReq(id), callId)
  }

  def rpcTypeByName(name: String, callId: Int) {
    analyzer ! RPCRequestEvent(TypeByNameReq(name), callId)
  }

  def rpcTypeByNameAtPoint(name: String, f: String, point: Int, callId: Int) {
    analyzer ! RPCRequestEvent(TypeByNameAtPointReq(name, new File(f), point), callId)
  }

  def rpcCallCompletion(id: Int, callId: Int) {
    analyzer ! RPCRequestEvent(CallCompletionReq(id), callId)
  }

  def rpcImportSuggestions(f: String, point: Int, names: List[String], maxResults: Int, callId: Int) {
    analyzer ! RPCRequestEvent(ImportSuggestionsReq(new File(f), point, names, maxResults), callId)
  }

  def rpcPublicSymbolSearch(names: List[String], maxResults: Int, callId: Int) {
    analyzer ! RPCRequestEvent(PublicSymbolSearchReq(names, maxResults), callId)
  }

  def rpcUsesOfSymAtPoint(f: String, point: Int, callId: Int) {
    analyzer ! RPCRequestEvent(UsesOfSymAtPointReq(new File(f), point), callId)
  }

  def rpcTypeAtPoint(f: String, point: Int, callId: Int) {
    analyzer ! RPCRequestEvent(TypeAtPointReq(new File(f), point), callId)
  }

  def rpcInspectPackageByPath(path: String, callId: Int) {
    analyzer ! RPCRequestEvent(InspectPackageByPathReq(path), callId)
  }

  def rpcPrepareRefactor(refactorType: Symbol, procId: Int, params: immutable.Map[Symbol, Any], interactive: Boolean, callId: Int) {
    analyzer ! RPCRequestEvent(RefactorPerformReq(
	procId, refactorType, params, interactive), callId)
  }

  def rpcExecRefactor(refactorType: Symbol, procId: Int, callId: Int) {
    analyzer ! RPCRequestEvent(RefactorExecReq(procId, refactorType), callId)
  }

  def rpcCancelRefactor(procId: Int, callId: Int) {
    analyzer ! RPCRequestEvent(RefactorCancelReq(procId), callId)
  }

  def rpcExpandSelection(filename: String, start: Int, stop: Int, callId: Int) {
    try {
      FileUtils.readFile(new File(filename)) match {
        case Right(contents) => {
          val selectionRange = Range(start, stop - start)
          AstSelector.expandSelection(contents, selectionRange) match {
            case Some(range) => sendRPCReturn(
              toWF(FileRange(filename, range.offset, range.offset + range.length)), callId)
            case _ => sendRPCReturn(
              toWF(FileRange(filename, start, stop)), callId)
          }
        }
        case Left(e) => throw e
      }
    } catch {
      case e: ScalaParserException =>
      sendRPCError(ErrFormatFailed,
        Some("Could not parse broken syntax: " + e), callId)
    }
  }

  def rpcFormatFiles(filenames: Iterable[String], callId: Int) {
    val files = filenames.map { new File(_) }
    try {
      val changeList = files.map { f =>
        FileUtils.readFile(f) match {
          case Right(contents) => {
            val formatted = ScalaFormatter.format(contents, config.formattingPrefs)
            Change(AbstractFile.getFile(f), 0, contents.length, formatted)
          }
          case Left(e) => throw e
        }
      }
      addUndo("Formatted source of " + filenames.mkString(", ") + ".",
        FileUtils.inverseChanges(changeList))
      FileUtils.writeChanges(changeList) match {
        case Right(_) => sendRPCAckOK(callId)
        case Left(e) =>
        sendRPCError(ErrFormatFailed,
          Some("Could not write any formatting changes: " + e), callId)
      }
    } catch {
      case e: ScalaParserException =>
      sendRPCError(ErrFormatFailed,
        Some("Cannot format broken syntax: " + e), callId)
    }
  }

}
