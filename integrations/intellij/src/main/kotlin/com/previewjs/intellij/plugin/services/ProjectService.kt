package com.previewjs.intellij.plugin.services

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.codeInsight.hints.presentation.RecursivelyUpdatingRootPresentation
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.previewjs.intellij.plugin.api.AnalyzeFileRequest
import com.previewjs.intellij.plugin.api.AnalyzedFileComponent
import com.previewjs.intellij.plugin.api.StartPreviewRequest
import com.previewjs.intellij.plugin.api.UpdatePendingFileRequest
import kotlinx.coroutines.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.*
import javax.swing.JComponent
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.concurrent.schedule

class ProjectService(private val project: Project) : Disposable {
    companion object {
        const val INLAY_PRIORITY = 1000
    }

    private val app = ApplicationManager.getApplication()
    private val service = app.getService(PreviewJsSharedService::class.java)
    private val editorManager = FileEditorManager.getInstance(project)
    private var refreshTimerTask: TimerTask? = null

    private val browser = JBCefBrowser()
    val browserComponent: JComponent
        get() = browser.component
    val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    init {
        Disposer.register(this, browser)
        val browserBase: JBCefBrowserBase = browser
        val linkHandler = JBCefJSQuery.create(browserBase)
        linkHandler.addHandler { link ->
            BrowserUtil.browse(link)
            return@addHandler null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                browser.executeJavaScript(
                    """
                        window.openInExternalBrowser = function(url) {
                            ${linkHandler.inject("url")}
                        };
                    """,
                    browser.url,
                    0
                );
            }
        }, browser.cefBrowser)
        browser.loadHTML(generateDefaultPreviewHtml(linkHandler))
        Disposer.register(browser, linkHandler)

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document)
                if (file != null && file.isInLocalFileSystem && file.isWritable && event.document.text.length <= 1_048_576) {
                    service.enqueueAction(project, { api ->
                        service.ensureWorkspaceReady(project, file.path) ?: return@enqueueAction
                        api.updatePendingFile(UpdatePendingFileRequest(
                                absoluteFilePath = file.path,
                                utf8Content = event.document.text
                        ))
                    }, {
                        "Warning: unable to update pending file ${file.path}\n\n${it.stackTraceToString()}"
                    })
                    refreshTimerTask?.cancel()
                    refreshTimerTask = Timer("PreviewJsHintRefresh", false).schedule(500) {
                        refreshTimerTask = null
                        app.invokeLater(Runnable {
                            updateComponents(file, event.document.text)
                        })
                    }
                }
            }
        }, this)

        project.messageBus.connect(project)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    updateComponents(file)
                }
            })

        for (file in editorManager.openFiles) {
            updateComponents(file)
        }
    }

    private fun updateComponents(file: VirtualFile, content: String? = null) {
        val fileEditors = editorManager.getEditors(file)
        service.enqueueAction(project, { api ->
            val workspaceId = service.ensureWorkspaceReady(project, file.path) ?: return@enqueueAction
            content?.let { content ->
                api.updatePendingFile(UpdatePendingFileRequest(
                        absoluteFilePath = file.path,
                        utf8Content = content
                ))
            }
            val components = api.analyzeFile(AnalyzeFileRequest(
                    workspaceId,
                    absoluteFilePath = file.path,
            )).components
            app.invokeLater(Runnable {
                updateComponentHints(file, fileEditors, components)
            })
        }, {
            "Warning: unable to find components in ${file.path}\n\n${it.stackTraceToString()}"
        })
    }

    private fun updateComponentHints(
        file: VirtualFile,
        fileEditors: Array<FileEditor>,
        components: List<AnalyzedFileComponent>
    ) {
        for (fileEditor in fileEditors) {
            if (fileEditor is TextEditor) {
                val editor = fileEditor.editor
                if (editor is EditorImpl) {
                    val presentationFactory = PresentationFactory(editor)
                    val offsets = HashSet<Int>()
                    for (component in components) {
                        offsets.add(component.offset)
                    }
                    val existingBlockByOffset = HashMap<Int, Inlay<*>>()
                    for (block in editor.inlayModel.getBlockElementsInRange(0, Int.MAX_VALUE)) {
                        if (offsets.contains(block.offset)) {
                            existingBlockByOffset[block.offset] = block
                        } else {
                            Disposer.dispose(block)
                        }
                    }
                    for (component in components) {
                        val existingBlock = existingBlockByOffset[component.offset]
                        if (existingBlock == null) {
                            editor.inlayModel.addBlockElement(
                                component.offset,
                                false,
                                true,
                                INLAY_PRIORITY,
                                InlineInlayRenderer(
                                    listOf(
                                        HorizontalConstrainedPresentation(
                                            RecursivelyUpdatingRootPresentation(
                                                presentationFactory.referenceOnHover(
                                                    presentationFactory.text("Open ${component.componentName} in Preview.js")
                                                ) { _, _ -> openPreview(file.path, component.componentId) }
                                            ),
                                            HorizontalConstraints(INLAY_PRIORITY, false)
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openPreview(absoluteFilePath: String, componentId: String) {
        val app = ApplicationManager.getApplication()
        service.enqueueAction(project, { api ->
            val workspaceId = service.ensureWorkspaceReady(project, absoluteFilePath) ?: return@enqueueAction
            val previewBaseUrl = api.startPreview(StartPreviewRequest(workspaceId)).url
            val previewUrl = "$previewBaseUrl?p=$componentId"
            app.invokeLater(Runnable {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Preview.js")
                val currentBrowserUrl = browser.cefBrowser.url
                if (currentBrowserUrl?.startsWith(previewBaseUrl) == true) {
                    browser.cefBrowser.executeJavaScript("window.__previewjs_navigate(\"${componentId}\");", previewUrl, 0)
                } else {
                    browser.loadURL(previewUrl)
                }
                toolWindow?.show()
            })
        }, {
            "Warning: unable to open preview\n\n${it.stackTraceToString()}"
        })
    }

    override fun dispose() {
        service.enqueueAction(project, {
            service.disposeWorkspaces(project)
        }, {
            "Warning: unable to dispose of workspaces\n\n${it.stackTraceToString()}"
        })
    }
}

