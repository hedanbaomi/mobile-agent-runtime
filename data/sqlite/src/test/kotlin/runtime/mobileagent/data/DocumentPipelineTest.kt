// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.*

class DocumentPipelineTest {
    private fun database() = JdbcSqlConnection().also { Migrations.apply(it) }
    private fun units(version: String = DocumentUnitPlanner.VERSION, dense: Boolean = false) =
        DocumentUnitPlanner(version).plan("synthetic-document",listOf(PlanningPage(1,"",true,dense=dense)))

    @Test fun terminalFactsAndDuplicateUsageAreImmutable() {
        database().use { db ->
            val store=DocumentPipelineStore(db); val unit=units().single()
            store.materialize("j","synthetic-document",unit.plannerVersion,listOf(unit))
            store.prepare("j",unit,"b","target","cache","request")
            assertTrue(store.dispatched("request"))
            assertFalse(store.dispatched("request"),"same dispatch capability is one-shot")
            val usage=VisionDiagnosticMetadata(dispatched=true,inputTokens=70,outputTokens=30,reasoningTokens=20)
            assertTrue(store.settle("request",PipelineAttemptState.SUCCEEDED,usage))
            assertFalse(store.settle("request",PipelineAttemptState.FAILED,VisionDiagnosticMetadata(inputTokens=999)))
            assertThrows(Exception::class.java) { db.execute("UPDATE pipeline_attempts SET input_tokens=999 WHERE request_id='request'") }
            assertEquals(70L,db.query("SELECT input_tokens FROM pipeline_attempts").single().long("input_tokens"))
            assertEquals(100L,store.progress("b").usage.totalTokens)
            assertEquals(20L,store.progress("b").usage.reasoningTokens)
        }
    }

    @Test fun unknownUsageIsNullAndReservationsSurviveRecovery() {
        database().use { db ->
            val store=DocumentPipelineStore(db); val unit=units().single()
            store.materialize("j","synthetic-document",unit.plannerVersion,listOf(unit))
            store.configure("b",PipelinePolicy(tokenDispatchCeiling=100,reservationTokensPerRequest=60))
            store.prepare("j",unit,"b","target","cache","first")
            store.dispatched("first")
            store.recover("j")
            assertFalse(store.dispatched("first"))
            assertFalse(store.settle("first",PipelineAttemptState.SUCCEEDED,VisionDiagnosticMetadata()))
            assertTrue(store.unknown("j",unit.unitId,1))
            assertNull(store.progress("b").usage.inputTokens)
            assertNull(store.progress("b").usage.totalTokens)
            assertEquals(60L,store.progress("b").usage.reservedTokens)
            assertEquals("PIPELINE_TOKEN_DISPATCH_CEILING",store.stopReason("b"))
        }
    }

    @Test fun changedPlannerAndTargetCannotBypassUnknownButAcknowledgedNewPlanCanFinish() {
        database().use { db ->
            val store=DocumentPipelineStore(db);val old=units("v1").single();val next=units("v2",dense=true)
            store.materialize("j","synthetic-document","v1",listOf(old))
            store.prepare("j",old,"b","target-A","cache-old","old")
            store.settle("old",PipelineAttemptState.UNKNOWN_OUTCOME,VisionDiagnosticMetadata(dispatched=true))
            store.materialize("j","synthetic-document","v2",next)
            assertEquals(next.size,store.reuse("j",next,"target-B",PIPELINE_CHUNK_VERSION).unknown)
            assertThrows(IllegalStateException::class.java) { store.prepare("j",next.first(),"b","target-B","cache-new","no") }
            store.authorizeUnknown("j")
            next.forEachIndexed { i,unit ->
                store.prepare("j",unit,"b","target-B","cache-$i","new-$i")
                store.settle("new-$i",PipelineAttemptState.SUCCEEDED,VisionDiagnosticMetadata())
            }
            assertEquals("UNKNOWN_OUTCOME",db.query("SELECT state FROM pipeline_attempts WHERE request_id='old'").single().string("state"))
            assertEquals(next.size+1,db.query("SELECT request_id FROM pipeline_attempts").size)
        }
    }

    @Test fun globalConcurrencyAndPolicyChangesAreBounded() {
        database().use { db ->
            val store=DocumentPipelineStore(db); val unit=units().single()
            store.materialize("j","synthetic-document",unit.plannerVersion,listOf(unit))
            store.prepare("j",unit,"b","t","c","one")
            assertEquals("PIPELINE_MAX_CONCURRENCY",store.stopReason("other-batch"))
            assertThrows(IllegalStateException::class.java) { store.configure("b",PipelinePolicy(tokenDispatchCeiling=50,reservationTokensPerRequest=10)) }
            store.settle("one",PipelineAttemptState.UNKNOWN_OUTCOME,VisionDiagnosticMetadata(dispatched=true))
            assertThrows(IllegalStateException::class.java) { store.configure("b",PipelinePolicy(tokenDispatchCeiling=50,reservationTokensPerRequest=10)) }
        }
    }

    @Test fun migrationPreservesLegacyCacheAndDoesNotInventPlans() {
        database().use { db ->
            db.execute("INSERT INTO vision_results(cache_key,asset_hash,context_hash,model_fingerprint,prompt_version,schema_version,status,ocr_text,description,table_markdown,result_type,processed_at) VALUES('legacy','asset','context','target','v1','v1','SUCCESS','saved','','','image','now')")
            db.execute("DELETE FROM schema_version")
            db.execute("INSERT INTO schema_version(version) VALUES(23)")
            // Reconstruct actual v23: none of the new tables, indexes or triggers exists.
            listOf("pipeline_plans","pipeline_units","pipeline_results","pipeline_attempts","pipeline_retry_permits","pipeline_policies","pipeline_publications").forEach {
                db.execute("DROP TABLE $it")
            }
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'pipeline_%'").isEmpty())
            Migrations.apply(db)
            assertEquals(24L,db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals("saved",db.query("SELECT ocr_text FROM vision_results WHERE cache_key='legacy'").single().string("ocr_text"))
            assertTrue(db.query("SELECT * FROM pipeline_plans").isEmpty())
            assertEquals(7,db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'pipeline_%'").size)
        }
    }

    @Test fun failedUsageIsRetainedAndConsecutiveFailuresStopFurtherRequests() {
        database().use { db ->
            val store=DocumentPipelineStore(db);val unit=units().single()
            store.materialize("j","synthetic-document",unit.plannerVersion,listOf(unit))
            store.configure("b",PipelinePolicy(consecutiveFailureLimit=2))
            repeat(2) { i ->
                store.prepare("j",unit,"b","target","cache","failed-$i")
                assertTrue(store.dispatched("failed-$i"))
                assertTrue(store.settle("failed-$i",PipelineAttemptState.FAILED,
                    VisionDiagnosticMetadata(dispatched=true,inputTokens=12,outputTokens=5,reasoningTokens=3,errorCode="OUTPUT_TRUNCATED")))
            }
            assertEquals("PIPELINE_FAILURE_LIMIT",store.stopReason("b"))
            assertThrows(IllegalStateException::class.java) { store.prepare("j",unit,"b","target","cache","third") }
            assertEquals(34L,store.progress("b").usage.totalTokens)
            assertEquals(6L,store.progress("b").usage.reasoningTokens)
            assertEquals(2,db.query("SELECT * FROM pipeline_attempts").size)
        }
    }

    @Test fun tenUnitResumeRetainsFourSuccessesAndNeverReplaysUnknownWithoutConfirmation() {
        val file=java.nio.file.Files.createTempFile("pipeline-restart-", ".sqlite").toFile()
        var db=JdbcSqlConnection("jdbc:sqlite:${file.absolutePath}")
        try {
            Migrations.apply(db)
            val blobs=MemoryBlobSink();val requests=mutableListOf<Int>();val renders=mutableListOf<Int>();var unknown=true
            val raster=PdfPageRasterizer { _,pages -> pages.map { renders+=it;RenderedPdfPage(it,byteArrayOf(it.toByte()),"image/png",1,1) } }
            val backend=VisionBackend { input ->
                assertTrue(input.beforeDispatch()); requests+=input.page!!
                if(input.page==5 && unknown) VisionOutcome.Unknown(VisionDiagnosticMetadata(dispatched=true))
                else VisionOutcome.Success(VisionSuccess("page ${input.page}","diagram"),VisionDiagnosticMetadata(dispatched=true,inputTokens=7,outputTokens=3,reasoningTokens=2))
            }
            fun repo(chunk: String=PIPELINE_CHUNK_VERSION,target:String="target")=KnowledgeRepository(db,blobs,vision=backend,visionModelFingerprint=target,pdfRasterizer=raster,chunkVersion=chunk)
            val first=repo();val batch=stage(first,tenPages())
            first.authorizeBatchVision(batch,"target");first.processBatch(batch,true)
            assertEquals((1..5).toList(),requests)
            assertEquals(4,first.batchPipelineProgress(batch).succeeded)
            assertEquals(1,first.batchPipelineProgress(batch).unknown)
            assertEquals(5,first.batchPipelineProgress(batch).pending)
            db.close()
            db=JdbcSqlConnection("jdbc:sqlite:${file.absolutePath}")
            Migrations.apply(db)
            val restarted=repo()
            restarted.recoverableBatchIds().forEach { restarted.processBatch(it,true) }
            assertEquals((1..5).toList(),requests)
            unknown=false
            val job=restarted.listBatchItemViews(batch).single().jobId!!
            assertThrows(IllegalStateException::class.java) { restarted.retryUnknownVision(job,false,"target") }
            assertEquals(ImportStage.READY,restarted.retryUnknownVision(job,true,"target").stage)
            assertEquals((1..5).toList()+(5..10).toList(),requests)
            assertEquals((1..5).toList()+(5..10).toList(),renders,"successful units reuse results before rasterization")
            assertEquals(10,restarted.batchPipelineProgress(batch).published)
            assertEquals(11,restarted.batchPipelineProgress(batch).usage.attempts)
            assertEquals(1,restarted.batchPipelineProgress(batch).usage.unknownUsageAttempts)
            val oldVersion=db.query("SELECT active_version_id FROM documents").single().string("active_version_id")
            val rechunk=repo("chunks-v2")
            assertEquals(10,rechunk.batchReuseSummary(batch).localRebuild)
            assertEquals(1,rechunk.rebuildBatchLocalChunks(batch))
            assertEquals(11,requests.size)
            assertNotEquals(oldVersion,db.query("SELECT active_version_id FROM documents").single().string("active_version_id"))
            assertEquals(0,rechunk.rebuildBatchLocalChunks(batch),"published marker prevents duplicate publication")
            val targetChanged=repo("chunks-v2","target-B")
            val diff=targetChanged.batchReuseSummary(batch,"target-B")
            assertEquals(10,diff.newRequests)
            targetChanged.reconfigureBatchPipeline(batch,"target-B",diff)
            targetChanged.processBatch(batch,true)
            assertEquals(21,requests.size)
            assertEquals(10,targetChanged.batchPipelineProgress(batch).published)
            val switchBack=repo("chunks-v2","target")
            val backPreview=switchBack.batchReuseSummary(batch,"target")
            assertEquals(10,backPreview.localRebuild)
            assertEquals(0,backPreview.newRequests)
            switchBack.reconfigureBatchPipeline(batch,"target",backPreview)
            assertEquals(0,switchBack.batchPipelineProgress(batch).published,"saved A is not yet the published B index")
            switchBack.processBatch(batch,true)
            assertEquals(21,requests.size,"returning to an existing target reuses its exact results")
            assertEquals(10,switchBack.batchPipelineProgress(batch).published)
            assertEquals(0,switchBack.rebuildBatchLocalChunks(batch),"updated publication pointer deduplicates the returned target")
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun pausePreservesInFlightSuccessAndCeilingStopsOnlyNewDispatch() {
        database().use { db ->
            val blobs=MemoryBlobSink();var requests=0;lateinit var repo:KnowledgeRepository;lateinit var batch:String
            val raster=PdfPageRasterizer { _,pages -> pages.map { RenderedPdfPage(it,byteArrayOf(it.toByte()),"image/png",1,1) } }
            repo=KnowledgeRepository(db,blobs,visionModelFingerprint="target",pdfRasterizer=raster,vision=VisionBackend { input ->
                assertTrue(input.beforeDispatch());requests++;repo.pauseBatch(batch)
                VisionOutcome.Success(VisionSuccess("kept","paid"),VisionDiagnosticMetadata(dispatched=true,inputTokens=20,outputTokens=10))
            })
            batch=stage(repo,tenPages());repo.configureBatchPipeline(batch,PipelinePolicy(tokenDispatchCeiling=60,reservationTokensPerRequest=40))
            repo.authorizeBatchVision(batch,"target");repo.processBatch(batch,true)
            assertEquals(1,requests);assertEquals(1,repo.batchPipelineProgress(batch).succeeded)
            repo.resumeBatch(batch);repo.processBatch(batch,true)
            assertEquals(1,requests,"30 observed + 40 reserved would exceed 60 ceiling")
            assertEquals(ImportBatchState.PAUSED,repo.findBatch(batch)!!.state)
        }
    }

    @Test fun finiteCeilingCannotForgetUnreservedLegacyUnknownSpend() {
        database().use { db ->
            val repo=KnowledgeRepository(db,MemoryBlobSink(),visionModelFingerprint="target",vision=VisionBackend { VisionOutcome.UnknownOutcome },
                pdfRasterizer=PdfPageRasterizer { _,pages -> pages.map { RenderedPdfPage(it,byteArrayOf(it.toByte()),"image/png",1,1) } })
            val batch=stage(repo,tenPages());repo.authorizeBatchVision(batch,"target");repo.processBatch(batch,true)
            // Synthetic pre-v24 checkpoint has a cache barrier but no new attempt/reservation ledger.
            db.execute("DELETE FROM pipeline_attempts")
            assertEquals(1,db.query("SELECT * FROM vision_results WHERE status='UNKNOWN_OUTCOME'").size)
            assertThrows(IllegalStateException::class.java) {
                repo.configureBatchPipeline(batch,PipelinePolicy(tokenDispatchCeiling=100,reservationTokensPerRequest=20))
            }
            assertNull(repo.batchPipelinePolicy(batch).tokenDispatchCeiling)
            assertEquals(1,db.query("SELECT * FROM vision_results WHERE status='UNKNOWN_OUTCOME'").size)
        }
    }

    @Test fun knownLegacyUsageCountsOnceAgainstCeilingAndInProgressFacts() {
        database().use { db ->
            val repo=KnowledgeRepository(db,MemoryBlobSink())
            val batch=stage(repo,tenPages());val job=repo.listBatchItemViews(batch).single().jobId!!
            db.execute("INSERT INTO vision_attempts(request_id,cache_key,job_id,asset_hash,attempt_no,status,stage,dispatch_status,input_tokens,output_tokens,created_at,updated_at) VALUES('legacy-known','cache',?,'asset',1,'SUCCESS','TERMINAL','RESPONSE_RECEIVED',90,10,'now','now')",listOf(job))
            repo.configureBatchPipeline(batch,PipelinePolicy(tokenDispatchCeiling=100,reservationTokensPerRequest=20))
            assertEquals("PIPELINE_TOKEN_DISPATCH_CEILING",DocumentPipelineStore(db).stopReason(batch))
            assertEquals(100L,repo.batchPipelineProgress(batch).usage.totalTokens)
            assertEquals(1,repo.batchPipelineProgress(batch).usage.attempts)
        }
    }

    @Test fun lateSuccessfulCallbackCannotOverwriteRecoveredUnknownAttemptOrCache() {
        database().use { db ->
            val repo=KnowledgeRepository(db,MemoryBlobSink(),visionModelFingerprint="target",vision=VisionBackend { input ->
                assertTrue(input.beforeDispatch())
                val job=db.query("SELECT job_id FROM pipeline_attempts WHERE request_id=?",listOf(input.requestId)).single().string("job_id")
                DocumentPipelineStore(db).recover(job)
                VisionOutcome.Success(VisionSuccess("late response","must stay gated"),VisionDiagnosticMetadata(dispatched=true,inputTokens=12,outputTokens=8))
            })
            val job=repo.importBytes("synthetic.png","image/png",byteArrayOf(1,2,3),visionConfigured=true,visionConsent=true)
            assertEquals(ImportStage.FAILED,job.stage)
            assertTrue(job.error!!.contains("UNKNOWN_OUTCOME"))
            assertEquals("UNKNOWN_OUTCOME",db.query("SELECT state FROM pipeline_attempts").single().string("state"))
            assertEquals("UNKNOWN_OUTCOME",db.query("SELECT status FROM vision_results").single().string("status"))
            assertTrue(db.query("SELECT * FROM pipeline_results").isEmpty())
        }
    }

    @Test fun changedPlannerCannotAutomaticallyDispatchUnderOldConsent() {
        database().use { db ->
            val blobs=MemoryBlobSink();var requests=0;lateinit var first:KnowledgeRepository;lateinit var batch:String
            val raster=PdfPageRasterizer { _,pages -> pages.map { RenderedPdfPage(it,byteArrayOf(it.toByte()),"image/png",1,1) } }
            val backend=VisionBackend { input ->
                assertTrue(input.beforeDispatch());requests++
                if(requests==1) first.pauseBatch(batch)
                VisionOutcome.Success(VisionSuccess("kept","page"))
            }
            first=KnowledgeRepository(db,blobs,vision=backend,visionModelFingerprint="target",pdfRasterizer=raster,plannerVersion="planner-v1")
            batch=stage(first,tenPages());first.authorizeBatchVision(batch,"target");first.processBatch(batch,true)
            assertEquals(1,requests)
            val next=KnowledgeRepository(db,blobs,vision=backend,visionModelFingerprint="target",pdfRasterizer=raster,plannerVersion="planner-v2")
            next.resumeBatch(batch);next.processBatch(batch,true)
            assertEquals(1,requests,"old authorization cannot dispatch a changed unit plan")
            assertEquals("planner-v1",db.query("SELECT planner_version FROM pipeline_plans").single().string("planner_version"))
            val preview=next.batchReuseSummary(batch,"target")
            next.reconfigureBatchPipeline(batch,"target",preview);next.processBatch(batch,true)
            assertEquals(10,requests,"same image cache is preserved even across a reviewed planner version")
            assertEquals(10,next.batchPipelineProgress(batch).published)
        }
    }

    @Test fun changedUnavailableTargetRequiresExplicitUnknownAcknowledgementAndKeepsSiblingCache() {
        database().use { db ->
            val blobs=MemoryBlobSink();var requests=0
            val raster=PdfPageRasterizer { _,pages -> pages.map { RenderedPdfPage(it,byteArrayOf(it.toByte()),"image/png",1,1) } }
            val original=KnowledgeRepository(db,blobs,pdfRasterizer=raster,visionModelFingerprint="target-A",vision=VisionBackend { input ->
                assertTrue(input.beforeDispatch());requests++;VisionOutcome.UnknownOutcome
            })
            val batch=stage(original,tenPages());original.authorizeBatchVision(batch,"target-A");original.processBatch(batch,true)
            assertEquals(1,requests)
            db.execute("INSERT INTO vision_results SELECT 'unrelated-target',asset_hash,context_hash,'target-C',prompt_version,schema_version,status,ocr_text,description,table_markdown,result_type,processed_at FROM vision_results LIMIT 1")
            val changed=KnowledgeRepository(db,blobs,pdfRasterizer=raster,visionModelFingerprint="target-B",plannerVersion="next-plan",vision=VisionBackend { input ->
                assertTrue(input.beforeDispatch());requests++;VisionOutcome.Success(VisionSuccess("new result","diagram"))
            })
            val summary=changed.batchReuseSummary(batch,"target-B")
            assertEquals(1,summary.unknown)
            assertThrows(IllegalStateException::class.java) { changed.reconfigureBatchPipeline(batch,"target-B",summary) }
            assertEquals(1,requests)
            changed.reconfigureBatchPipeline(batch,"target-B",summary,acknowledgeDuplicateCharge=true)
            changed.processBatch(batch,true)
            assertEquals(11,requests)
            assertEquals(10,changed.batchPipelineProgress(batch).published)
            assertEquals("UNKNOWN_OUTCOME",db.query("SELECT state FROM pipeline_attempts WHERE target='target-A'").single().string("state"))
            assertEquals("UNKNOWN_OUTCOME",db.query("SELECT status FROM vision_results WHERE cache_key='unrelated-target'").single().string("status"))
        }
    }

    @Test fun actualDenseMixedAndTablePagesExecuteRegionRequestsWithNativeProvenance() {
        listOf("mixed native marker", "dense "+"retained native ".repeat(650), "Name | Count\nItem | 3").forEach { native ->
            database().use { db ->
                val requested=mutableListOf<ProcessingUnit>()
                val renderer=object : PdfPageRasterizer,PdfUnitRasterizer {
                    override fun render(pdfBytes:ByteArray,pages:List<Int>)=error("must render planned units")
                    override fun renderUnit(pdfBytes:ByteArray,unit:ProcessingUnit,limits:UnitRenderLimits):RenderedPdfPage {
                        requested+=unit
                        return RenderedPdfPage(unit.page,unit.unitId.toByteArray(),"image/png",20,20)
                    }
                }
                val repo=KnowledgeRepository(db,MemoryBlobSink(),pdfRasterizer=renderer,visionModelFingerprint="target",vision=VisionBackend { input ->
                    assertTrue(input.beforeDispatch())
                    VisionOutcome.Success(VisionSuccess("region OCR","diagram","| Name | Count |\n| Item | 3 |"),
                        VisionDiagnosticMetadata(dispatched=true,inputTokens=8,outputTokens=4,reasoningTokens=1))
                })
                val batch=stage(repo,complexPage(native));repo.authorizeBatchVision(batch,"target");repo.processBatch(batch,true)
                assertTrue(requested.size>=2)
                assertTrue(requested.all { it.kind==ProcessingUnitKind.REGION && it.region!=null && it.coverage.page==1 })
                assertTrue(requested.all { native.take(12) in it.nativeText })
                assertEquals(requested.size,repo.batchPipelineProgress(batch).published)
                assertEquals(requested.size*12L,repo.batchPipelineProgress(batch).usage.totalTokens)
                assertTrue(repo.search("region OCR").isNotEmpty())
                assertTrue(db.query("SELECT source_span FROM chunks").any { it.string("source_span").contains("pdf-unit-") })
            }
        }
    }

    @Test fun passivePreviewNeverReadsSourceBlobs() {
        database().use { db ->
            val backing=MemoryBlobSink();var reads=0
            val sink=object:BlobSink by backing { override fun get(sha256:String):ByteArray? { reads++;return backing.get(sha256) } }
            val repo=KnowledgeRepository(db,sink)
            val batch=stage(repo,tenPages());repo.processBatch(batch,false)
            reads=0
            repeat(3) { repo.batchReuseSummary(batch,refreshPlan=false) }
            assertEquals(0,reads)
            repo.batchReuseSummary(batch,refreshPlan=true)
            assertTrue(reads>0,"explicit review refreshes source metadata")
        }
    }

    @Test fun localRechunkPreservesNullableOfficePageAndExactSection() {
        database().use { db ->
            val out=java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { zip ->
                mapOf("word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>retained native context</w:t></w:r></w:p></w:body></w:document>".toByteArray(),
                    "word/media/figure.png" to byteArrayOf(1,2,3)).forEach { (name,bytes) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(bytes);zip.closeEntry()
                }
            }
            val blobs=MemoryBlobSink();var calls=0
            val backend=VisionBackend { input -> assertNull(input.page);assertTrue(input.beforeDispatch());calls++;VisionOutcome.Success(VisionSuccess("orphan media","diagram")) }
            val first=KnowledgeRepository(db,blobs,vision=backend,visionModelFingerprint="target")
            val kb=first.ensureDefaultBase();val batch=first.beginBatch(kb,ImportBatchKind.FILES,"nullable provenance")
            val job=first.importBytes("source.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",out.toByteArray(),true,kb,pauseAt=ImportStage.COPYING)
            first.bindJobToBatch(batch,job,"source.docx");first.authorizeBatchVision(batch,"target");first.processBatch(batch,true)
            fun visible()=db.query("SELECT c.page,c.source_span,c.text FROM chunks c JOIN documents d ON d.active_version_id=c.document_version_id WHERE c.asset_ids<>'' ORDER BY c.text,c.source_span")
                .map { Triple(it.longOrNull("page"),it.string("source_span"),it.string("text")) }
            val before=visible();assertTrue(before.isNotEmpty());assertTrue(before.all { it.first==null && it.second.contains("word/media/figure.png") })
            val changed=KnowledgeRepository(db,blobs,vision=backend,visionModelFingerprint="target",chunkVersion="local-v2")
            assertEquals(1,changed.rebuildBatchLocalChunks(batch))
            assertEquals(1,calls)
            assertEquals(before,visible())
        }
    }

    private fun complexPage(text:String):ByteArray {
        val escaped=text.replace("\\","\\\\").replace("(","\\(").replace(")","\\)")
        val content="BT /F1 12 Tf 10 10 Td ($escaped) Tj ET\n"+"0 0 10 10 re f\n".repeat(12)
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"+
            "2 0 obj << /Type /Pages /Count 1 /Kids [3 0 R] >> endobj\n"+
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >> endobj\n"+
            "4 0 obj << /Length ${content.toByteArray().size} >>\nstream\n${content}endstream\nendobj\n"+
            "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF").toByteArray()
    }


    private fun stage(repo:KnowledgeRepository,bytes:ByteArray):String {
        val kb=repo.ensureDefaultBase();val batch=repo.beginBatch(kb,ImportBatchKind.FILES,"synthetic pipeline")
        val job=repo.importBytes("ten.pdf","application/pdf",bytes,false,kb,pauseAt=ImportStage.COPYING)
        repo.bindJobToBatch(batch,job,"ten.pdf");return batch
    }
    private fun tenPages():ByteArray = buildString {
        val drawing="0 0 100 100 re f\n"
        append("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        append("2 0 obj << /Type /Pages /Count 10 /Kids [")
        for(page in 1..10) append("${page+2} 0 R ")
        append("] >> endobj\n")
        for(page in 1..10) {
            append("${page+2} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents ${page+12} 0 R >> endobj\n")
            append("${page+12} 0 obj << /Length ${drawing.length} >>\nstream\n${drawing}endstream\nendobj\n")
        }
        append("trailer << /Root 1 0 R >>\n%%EOF")
    }.toByteArray()

    @Test fun f1_chargedReservationAccountsForObservedTokensAndBlocksCeiling() {
        database().use { db ->
            val store = DocumentPipelineStore(db)
            val unit1 = units().single()
            val unit2 = DocumentUnitPlanner(DocumentUnitPlanner.VERSION).plan("synthetic-document-2", listOf(PlanningPage(2, "", true))).single()
            val unit3 = DocumentUnitPlanner(DocumentUnitPlanner.VERSION).plan("synthetic-document-3", listOf(PlanningPage(3, "", true))).single()
            val unit4 = DocumentUnitPlanner(DocumentUnitPlanner.VERSION).plan("synthetic-document-4", listOf(PlanningPage(4, "", true))).single()
            store.materialize("j1", "synthetic-document", unit1.plannerVersion, listOf(unit1))
            store.materialize("j2", "synthetic-document-2", unit2.plannerVersion, listOf(unit2))
            store.materialize("j3", "synthetic-document-3", unit3.plannerVersion, listOf(unit3))
            store.materialize("j4", "synthetic-document-4", unit4.plannerVersion, listOf(unit4))
            store.configure("b", PipelinePolicy(tokenDispatchCeiling = 100, reservationTokensPerRequest = 20))

            // Case A: UNKNOWN with input=90, output=30 (total 120) and reservation=20
            store.prepare("j1", unit1, "b", "target", "cache-1", "req-1")
            assertTrue(store.dispatched("req-1"))
            val unknownMetadata = VisionDiagnosticMetadata(dispatched = true, inputTokens = 90, outputTokens = 30, reasoningTokens = 20)
            assertTrue(store.settle("req-1", PipelineAttemptState.UNKNOWN_OUTCOME, unknownMetadata))

            // Verify chargedReservation accounts for observed tokens (120 >= 20 reservation)
            // Ceiling is 100, spent is 120, next reservation is 20 -> 20 > 100 - 120 (-20) -> BLOCKED
            assertEquals("PIPELINE_TOKEN_DISPATCH_CEILING", store.stopReason("b"))
            assertThrows(IllegalStateException::class.java) {
                store.prepare("j2", unit2, "b", "target", "cache-2", "req-2")
            }

            // Verify reasoning tokens are NOT double-counted in usage: 90 + 30 = 120 (not 140)
            assertEquals(120L, store.progress("b").usage.reservedTokens)
            assertEquals(90L, store.progress("b").usage.inputTokens)
            assertEquals(30L, store.progress("b").usage.outputTokens)

            // Case B: In a new batch with ceiling 130, FAILED with partial usage: input=120, output=null, reservation=20
            store.configure("b2", PipelinePolicy(tokenDispatchCeiling = 130, reservationTokensPerRequest = 20))
            store.prepare("j3", unit3, "b2", "target", "cache-3", "req-failed-partial")
            assertTrue(store.dispatched("req-failed-partial"))
            assertTrue(store.settle("req-failed-partial", PipelineAttemptState.FAILED, VisionDiagnosticMetadata(dispatched = true, inputTokens = 120)))
            // Observed lower bound is 120, reservation is 20 -> charged is 120.
            // 20 > 130 - 120 (10) -> BLOCKED
            assertEquals("PIPELINE_TOKEN_DISPATCH_CEILING", store.stopReason("b2"))

            // Case C: UNKNOWN with input=null, output=null, reservation=20 -> charged is reservation (20)
            store.configure("b3", PipelinePolicy(tokenDispatchCeiling = 100, reservationTokensPerRequest = 20))
            store.prepare("j4", unit4, "b3", "target", "cache-4", "req-unknown-null")
            assertTrue(store.dispatched("req-unknown-null"))
            assertTrue(store.settle("req-unknown-null", PipelineAttemptState.UNKNOWN_OUTCOME, VisionDiagnosticMetadata(dispatched = true)))
            // Spent is 20, remaining is 80, next reservation 20 <= 80 -> NOT blocked by ceiling
            assertNull(store.stopReason("b3"))
        }
    }

    @Test fun f2_persistedDiagnosticUsageSurvivesCrashAndReopenBeforeSettle() {
        val file = java.nio.file.Files.createTempFile("pipeline-f2-", ".sqlite").toFile()
        var db = JdbcSqlConnection("jdbc:sqlite:${file.absolutePath}")
        try {
            Migrations.apply(db)
            val store = DocumentPipelineStore(db)
            val unit = units().single()
            store.materialize("j", "synthetic-document", unit.plannerVersion, listOf(unit))
            db.execute("INSERT INTO knowledge_bases(id,name,created_at) VALUES ('kb','kb-name','now')")
            db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format) VALUES ('doc','kb','asset','doc','PDF')")
            db.execute("INSERT INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,updated_at,batch_id) VALUES ('j','kb','doc','name','VISION_PROCESSING',1,'now','b')")
            store.configure("b", PipelinePolicy(tokenDispatchCeiling = 200, reservationTokensPerRequest = 20))
            store.prepare("j", unit, "b", "target", "cache-crash", "req-crash")
            assertTrue(store.dispatched("req-crash"))

            // Fault Injection Window: diagnostics has written durable usage to vision_attempts,
            // but pipeline.settle has NOT been called and pipeline_attempts has null usage.
            db.execute(
                "INSERT INTO vision_attempts(request_id,cache_key,job_id,asset_hash,attempt_no,status,stage,dispatch_status,input_tokens,output_tokens,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf("req-crash", "cache-crash", "j", "asset", 1, "IN_PROGRESS", "NETWORK", "DISPATCHED", 90, 30, Utc.nowIso(), Utc.nowIso())
            )
            val preCrashAttempt = db.query("SELECT * FROM pipeline_attempts WHERE request_id='req-crash'").single()
            assertNull(preCrashAttempt.longOrNull("input_tokens"))
            assertNull(preCrashAttempt.longOrNull("output_tokens"))

            // Simulate crash: close db and reopen
            db.close()
            db = JdbcSqlConnection("jdbc:sqlite:${file.absolutePath}")
            Migrations.apply(db)
            val restartedStore = DocumentPipelineStore(db)

            // Before recover() is called, progress already coalesces durable usage facts from vision_attempts
            assertEquals(120L, restartedStore.progress("b").usage.reservedTokens)

            // Crash recovery
            restartedStore.recover("j")

            // 1. Attempt state must be UNKNOWN_OUTCOME
            val attempt = db.query("SELECT * FROM pipeline_attempts WHERE request_id='req-crash'").single()
            assertEquals("UNKNOWN_OUTCOME", attempt.string("state"))

            // 2. Known usage facts must NOT be dropped or zeroed: 90 input, 30 output coalesced into pipeline_attempts
            assertEquals(90L, attempt.long("input_tokens"))
            assertEquals(30L, attempt.long("output_tokens"))

            // 3. Charged tokens reflects 120 (not fallen back to 20 reservation, not double-counted 240)
            assertEquals(120L, restartedStore.progress("b").usage.reservedTokens)
            assertEquals(90L, restartedStore.progress("b").usage.inputTokens)
            assertEquals(30L, restartedStore.progress("b").usage.outputTokens)
            assertEquals(120L, restartedStore.progress("b").usage.totalTokens)
            assertEquals(1, restartedStore.progress("b").unknown)

            // 4. Terminal attempt is immutable: late response cannot settle or rewrite
            assertFalse(restartedStore.settle("req-crash", PipelineAttemptState.SUCCEEDED, VisionDiagnosticMetadata(dispatched = true, inputTokens = 999)))
            assertFalse(restartedStore.dispatched("req-crash"))
            assertEquals("UNKNOWN_OUTCOME", db.query("SELECT state FROM pipeline_attempts WHERE request_id='req-crash'").single().string("state"))
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun f3_densePageSplitsRequestTextWithinBoundsAndCoversFullOriginalText() {
        database().use { db ->
            val blobs = MemoryBlobSink()
            val capturedPrompts = mutableListOf<String>()
            val originalText = buildString {
                for (i in 1..2000) {
                    append("Section $i: This is detailed domain content paragraph $i with analysis and observations.\n")
                }
            } // ~180,000 characters
            assertTrue(originalText.length > 50_000)

            val rasterizer = object : PdfPageRasterizer, PdfUnitRasterizer {
                override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> =
                    pages.map { RenderedPdfPage(it, byteArrayOf(1), "image/png", 100, 100) }
                override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage =
                    RenderedPdfPage(unit.page, byteArrayOf(1, 2, 3), "image/png", 100, 100)
            }

            val backend = VisionBackend { input ->
                capturedPrompts += input.surroundingText
                VisionOutcome.Success(
                    VisionSuccess(ocrText = "OCR: " + input.surroundingText.take(50), semanticDescription = "Diagram", tableMarkdown = ""),
                    VisionDiagnosticMetadata(dispatched = true, inputTokens = 50, outputTokens = 20)
                )
            }

            val repo = KnowledgeRepository(db, blobs, pdfRasterizer = rasterizer, vision = backend, visionModelFingerprint = "target")
            val batch = stage(repo, complexPage(originalText))
            repo.authorizeBatchVision(batch, "target")
            repo.processBatch(batch, true)

            // Assertions:
            // 1. Multiple units planned for the dense page
            assertTrue(capturedPrompts.size >= 2)

            // 2. Each request's surrounding text is strictly bounded (<= 8,500 characters)
            assertTrue(capturedPrompts.all { it.length <= 8_500 }, "Request text must be bounded to <= 8,500 chars, got: ${capturedPrompts.map { it.length }}")

            // 3. Complete text coverage: concatenation of all surroundingText equals the original text exactly!
            val reconstructed = capturedPrompts.joinToString("")
            assertEquals(originalText.trim(), reconstructed, "Slices must cover original text exactly without gaps or overlaps")

            // 4. Geometric sanity: no degenerate 0-height slivers; full coverage without gaps
            val plannedUnits = db.query("SELECT unit_json FROM pipeline_units WHERE active=1")
                .map { kotlinx.serialization.json.Json.decodeFromString<ProcessingUnit>(it.string("unit_json")) }
            assertEquals(capturedPrompts.size, plannedUnits.size)
            assertTrue(plannedUnits.all { it.coverage.region.bottom > it.coverage.region.top && it.coverage.region.right > it.coverage.region.left }, "All regions must have positive dimensions")
            val totalArea = plannedUnits.sumOf { (it.coverage.region.right - it.coverage.region.left).toLong() * (it.coverage.region.bottom - it.coverage.region.top) }
            assertEquals(UnitRegion.SCALE.toLong() * UnitRegion.SCALE, totalArea, "Regions must cover the full page area exactly")

            // 5. Recovery / re-run does NOT resend already completed units
            val promptsCount = capturedPrompts.size
            repo.processBatch(batch, true)
            assertEquals(promptsCount, capturedPrompts.size, "Already succeeded units must not be re-dispatched")
        }
    }

    @Test fun f4_localRenderFailureMarksUnitFailedWithoutDispatchAndRecovers() {
        database().use { db ->
            val blobs = MemoryBlobSink()
            var networkDispatches = 0
            var renderAttempts = 0
            var shouldFailPage2Render = true

            val rasterizer = object : PdfPageRasterizer, PdfUnitRasterizer {
                override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> =
                    pages.filterNot { it == 2 && shouldFailPage2Render }
                        .map { RenderedPdfPage(it, byteArrayOf(1), "image/png", 100, 100) }
                override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage? {
                    renderAttempts++
                    if (unit.page == 2 && shouldFailPage2Render) return null
                    return RenderedPdfPage(unit.page, byteArrayOf(1, 2, 3), "image/png", 100, 100)
                }
            }

            val backend = VisionBackend { input ->
                networkDispatches++
                VisionOutcome.Success(VisionSuccess("ocr result", "desc"), VisionDiagnosticMetadata(dispatched = true, inputTokens = 10, outputTokens = 5))
            }

            val repo = KnowledgeRepository(db, blobs, pdfRasterizer = rasterizer, vision = backend, visionModelFingerprint = "target")
            val batch = stage(repo, twoPagesPdf())
            repo.authorizeBatchVision(batch, "target")

            // First run: Page 1 succeeds with network dispatch; Page 2 renderer fails locally
            repo.processBatch(batch, true)

            // Assert: Only 1 network dispatch occurred (for Page 1); Page 2 had 0 network attempts
            assertEquals(1, networkDispatches)

            val units = db.query("SELECT page, state, failure_code, failure_phase FROM pipeline_units WHERE active=1 ORDER BY page")
            assertEquals(2, units.size)
            assertEquals("SUCCEEDED", units[0].string("state"))
            assertNull(units[0].string("failure_code").ifBlank { null })

            assertEquals("FAILED", units[1].string("state"))
            assertEquals("RENDER_FAILED", units[1].string("failure_code"))
            assertEquals("LOCAL_RENDER", units[1].string("failure_phase"))

            // Assert: Pipeline progress reflects succeeded=1, failed=1, inFlight=0
            val progress = repo.batchPipelineProgress(batch)
            assertEquals(1, progress.succeeded)
            assertEquals(1, progress.failed)
            assertEquals(0, progress.inFlight)

            // Repair renderer for Page 2
            shouldFailPage2Render = false

            // Re-run: resume the job
            val job = repo.listBatchItemViews(batch).single().jobId!!
            repo.resumeImport(job, visionConfigured = true)

            // Assert: Page 1 was REUSED (0 new network dispatches), Page 2 succeeded
            assertEquals(2, networkDispatches, "Prior succeeded unit must not be re-dispatched; only repaired unit dispatches")
            val progressAfterResume = repo.batchPipelineProgress(batch)
            assertEquals(2, progressAfterResume.units)
            assertEquals(2, progressAfterResume.published)
            assertEquals(2, progressAfterResume.succeeded)
            assertEquals(0, progressAfterResume.failed)

            // Running again reuses all saved results with 0 new network dispatches
            repo.processBatch(batch, true)
            assertEquals(2, networkDispatches)
        }
    }

    private fun twoPagesPdf(): ByteArray = buildString {
        val drawing = "0 0 100 100 re f\n"
        append("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        append("2 0 obj << /Type /Pages /Count 2 /Kids [3 0 R 5 0 R] >> endobj\n")
        append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >> endobj\n")
        append("4 0 obj << /Length ${drawing.toByteArray().size} >>\nstream\n${drawing}endstream\nendobj\n")
        append("5 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 6 0 R >> endobj\n")
        append("6 0 obj << /Length ${drawing.toByteArray().size} >>\nstream\n${drawing}endstream\nendobj\n")
        append("trailer << /Root 1 0 R >>\n%%EOF")
    }.toByteArray()
}
