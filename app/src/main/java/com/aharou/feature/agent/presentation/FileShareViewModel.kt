package com.aharou.feature.agent.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.prepareWorkspaceShare
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FileShareViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val _sharingPaths = MutableStateFlow<Set<String>>(emptySet())
    val sharingPaths = _sharingPaths.asStateFlow()

    fun share(path: String, onReady: (Result<File>) -> Unit) {
        if (path in _sharingPaths.value) return
        val access = fileAccess.forWorkspace(workspaceRepository.currentPath()).forStrictRead()
        _sharingPaths.value += path
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        Result.success(prepareWorkspaceShare(access, path, context.cacheDir))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.w("FileShare", "Could not prepare file share", e)
                        Result.failure(e)
                    }
                }
                onReady(result)
            } finally {
                _sharingPaths.value -= path
            }
        }
    }
}
