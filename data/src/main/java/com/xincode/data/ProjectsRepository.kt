package com.xincode.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for project + session grouping operations.
 */
class ProjectsRepository(private val database: AppDatabase) {

    private val projectDao = database.projectDao()
    private val sessionDao = database.sessionDao()

    suspend fun createProject(name: String): Long {
        return projectDao.insert(ProjectEntity(name = name))
    }

    suspend fun renameProject(id: Long, name: String) {
        projectDao.rename(id, name)
    }

    suspend fun setProjectWorkspaceRoot(id: Long, root: String) {
        projectDao.setWorkspaceRoot(id, root.trim())
    }

    suspend fun deleteProject(id: Long) {
        database.inTransaction {
            // Move all sessions in this project to ungrouped first
            sessionDao.ungroupAllInProject(id)
            val project = projectDao.getById(id) ?: return@inTransaction
            projectDao.delete(project)
        }
    }

    suspend fun toggleExpanded(id: Long) {
        val project = projectDao.getById(id) ?: return
        projectDao.setExpanded(id, !project.isExpanded)
    }

    suspend fun moveSessionToProject(sessionId: Long, projectId: Long?) {
        sessionDao.moveToProject(sessionId, projectId)
    }

    suspend fun setSessionStarred(sessionId: Long, starred: Boolean) {
        sessionDao.setStarred(sessionId, starred)
    }

    suspend fun renameSession(sessionId: Long, title: String) {
        sessionDao.rename(sessionId, title)
    }
}