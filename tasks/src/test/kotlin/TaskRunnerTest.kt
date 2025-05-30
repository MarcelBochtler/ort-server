/*
 * Copyright (C) 2025 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.eclipse.apoapsis.ortserver.tasks

import com.typesafe.config.ConfigFactory

import io.kotest.core.spec.style.WordSpec
import io.kotest.extensions.system.withEnvironment
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.types.beInstanceOf

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import org.eclipse.apoapsis.ortserver.dao.test.mockDatabaseModule
import org.eclipse.apoapsis.ortserver.dao.test.unmockDatabaseModule
import org.eclipse.apoapsis.ortserver.dao.test.verifyDatabaseModuleIncluded
import org.eclipse.apoapsis.ortserver.tasks.impl.DeleteOldOrtRunsTask
import org.eclipse.apoapsis.ortserver.tasks.impl.DeleteOrphanedEntitiesTask
import org.eclipse.apoapsis.ortserver.tasks.impl.kubernetes.LongRunningJobsFinderTask
import org.eclipse.apoapsis.ortserver.tasks.impl.kubernetes.LostJobsFinderTask
import org.eclipse.apoapsis.ortserver.tasks.impl.kubernetes.ReaperTask
import org.eclipse.apoapsis.ortserver.transport.testing.TEST_TRANSPORT_NAME

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.KoinTest

class TaskRunnerTest : KoinTest, WordSpec() {
    init {
        beforeTest {
            mockDatabaseModule()
        }

        afterTest {
            unmockDatabaseModule()
        }

        "runTasks" should {
            "execute a single task" {
                val taskLog = checkTaskExecution("task1")

                taskLog.getExecutedTasks() shouldContainExactly setOf("task1")
            }

            "execute multiple tasks" {
                val taskLog = checkTaskExecution("task1", "task2")

                taskLog.getExecutedTasks() shouldContainExactlyInAnyOrder setOf("task1", "task2")
            }

            "handle errors during task execution" {
                val taskLog = checkTaskExecution("task1", ERROR_TASK, "task2")

                taskLog.getExecutedTasks() shouldContainExactlyInAnyOrder setOf("task1", ERROR_TASK, "task2")
            }

            "handle a non-existing task" {
                val taskLog = checkTaskExecution("task1", "nonExistingTask", "task2")

                taskLog.getExecutedTasks() shouldContainExactlyInAnyOrder setOf("task1", "task2")
            }
        }

        "main" should {
            "call runTasks with a database module" {
                checkMain { koin ->
                    verifyDatabaseModuleIncluded()
                }
            }
        }

        "the task module" should {
            "include a task to delete old ORT runs" {
                checkMain { koin ->
                    val task = koin.get<Task>(named("delete-old-ort-runs"))
                    task should beInstanceOf<DeleteOldOrtRunsTask>()
                }
            }

            "include a task to delete orphan entities" {
                checkMain { koin ->
                    val task = koin.get<Task>(named("delete-orphaned-entities"))
                    task should beInstanceOf<DeleteOrphanedEntitiesTask>()
                }
            }

            "include a task for the Kubernetes job reaper" {
                checkMain { koin ->
                    val task = koin.get<Task>(named("kubernetes-reaper"))
                    task should beInstanceOf<ReaperTask>()
                }
            }

            "include a task for the Kubernetes lost jobs finder" {
                checkMain { koin ->
                    val task = koin.get<Task>(named("kubernetes-lost-jobs-finder"))
                    task should beInstanceOf<LostJobsFinderTask>()
                }
            }

            "include a task for the Kubernetes long-running jobs finder" {
                checkMain { koin ->
                    val task = koin.get<Task>(named("kubernetes-long-running-jobs-finder"))
                    task should beInstanceOf<LongRunningJobsFinderTask>()
                }
            }
        }
    }
}

/**
 * Call the task runner function to execute the tasks with the given [taskNames] and return a [TaskLog] with
 * information about the executed tasks. Optionally, set the property to exit the JVM to the given [exitFlag].
 */
private suspend fun checkTaskExecution(vararg taskNames: String, exitFlag: Boolean = false): TaskLog {
    val tasksToExecute = taskNames.joinToString(",")
    val taskLog = TaskLog()

    val environment = mapOf("TASKS" to tasksToExecute, "TASKS_EXIT_JVM" to exitFlag.toString())
    withEnvironment(environment) {
        ConfigFactory.invalidateCaches()
        runTasks(listOf(configModule(), createTestModule(taskLog)))
    }

    return taskLog
}

/**
 * Run a test with the `main` function. Call the given [block] with a Koin instance that is configured with the modules
 * passed to [runTasks]. The [block] can then test for the presence of certain bean instances. Set the environment
 * variable to keep the JVM alive to the given [keepAliveFlag].
 */
private suspend fun checkMain(keepAliveFlag: Boolean = true, block: (Koin) -> Unit) {
    mockkStatic(::runTasks)
    try {
        coEvery { runTasks(any()) } just runs

        val environment = mapOf(
            "ORCHESTRATOR_SENDER_TRANSPORT_TYPE" to TEST_TRANSPORT_NAME,
            "TASKS_KEEP_JVM" to keepAliveFlag.toString()
        )
        withEnvironment(environment) {
            ConfigFactory.invalidateCaches()

            main()
        }

        val captModules = slot<List<Module>>()
        coVerify { runTasks(capture(captModules)) }

        val app = startKoin {
            modules(captModules.captured)
        }
        block(app.koin)
    } finally {
        stopKoin()
        unmockkStatic(::runTasks)
    }
}

/**
 * A simple helper class used by the test tasks to record their execution.
 */
private class TaskLog {
    /** A map storing the task log. */
    private val taskMap: ConcurrentMap<String, Boolean> = ConcurrentHashMap()

    /**
     * Record in this log that the task with the given [taskName] was executed.
     */
    fun recordTaskExecution(taskName: String) {
        taskMap[taskName] = true
    }

    /**
     * Return a [Set] with the names of the task that have been executed.
     */
    fun getExecutedTasks(): Set<String> = taskMap.keys
}

/**
 * A base class for test tasks. During execution, the task adds an entry to the [taskLog] map to record that it was
 * triggered.
 */
private open class TestTaskBase(
    /** A task name to identify the task in the task log. */
    private val name: String,

    /** A map to log which tasks were triggered. */
    private val taskLog: TaskLog
) : Task {
    override suspend fun execute() {
        taskLog.recordTaskExecution(name)
    }
}

/** The name of a task implementation that throws an exception. */
private const val ERROR_TASK = "errorTask"

/**
 * A special test task implementation that throws an exception during its execution. This is used to test the
 * exception handling of the task runner.
 */
private class ErrorTask(taskLog: TaskLog) : TestTaskBase(ERROR_TASK, taskLog) {
    override suspend fun execute() {
        super.execute()
        throw IOException("Test exception: Task execution failed.")
    }
}

/**
 * Create a [Module] that contains all test tasks and their dependencies.
 */
private fun createTestModule(taskLog: TaskLog): Module =
    module {
        single { taskLog }
        single<Task>(named("task1")) { TestTaskBase("task1", get()) }
        single<Task>(named("task2")) { TestTaskBase("task2", get()) }
        single<Task>(named(ERROR_TASK)) { ErrorTask(get()) }
    }
