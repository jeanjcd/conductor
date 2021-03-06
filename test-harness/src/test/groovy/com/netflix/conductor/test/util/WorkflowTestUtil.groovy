package com.netflix.conductor.test.util

import com.netflix.conductor.common.metadata.tasks.Task
import com.netflix.conductor.common.metadata.tasks.TaskDef
import com.netflix.conductor.common.metadata.workflow.WorkflowDef
import com.netflix.conductor.common.run.Workflow
import com.netflix.conductor.core.WorkflowContext
import com.netflix.conductor.core.execution.ApplicationException
import com.netflix.conductor.core.execution.WorkflowExecutor
import com.netflix.conductor.dao.QueueDAO
import com.netflix.conductor.service.ExecutionService
import com.netflix.conductor.service.MetadataService
import com.netflix.conductor.tests.utils.JsonUtils
import org.apache.commons.lang.StringUtils

import javax.annotation.PostConstruct
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This is a helper class used to initialize task definitions required by the tests when loaded up.
 * The task definitions that are loaded up in {@link WorkflowTestUtil#taskDefinitions()} method as part of the post construct of the bean.
 * This class is intended to be used in the Spock integration tests and provides helper methods to:
 * <ul>
 *     <li> Terminate all the  running Workflows</li>
 *     <li> Get the persisted task definition based on the taskName</li>
 *     <li> pollAndFailTask </li>
 *     <li> pollAndCompleteTask </li>
 *     <li> verifyPolledAndAcknowledgedTask </li>
 * </ul>
 *
 * Usage: Inject this class in any Spock based specification:
 * <code>
 *      @Inject
 *     WorkflowTestUtil workflowTestUtil
 * </code>
 */
@Singleton
class WorkflowTestUtil {

    private final MetadataService metadataService
    private final ExecutionService workflowExecutionService
    private final WorkflowExecutor workflowExecutor
    private final QueueDAO queueDAO
    private static final int RETRY_COUNT = 1
    private static final String TEMP_FILE_PATH = "/input.json"

    @Inject
    WorkflowTestUtil(MetadataService metadataService, ExecutionService workflowExecutionService,
                     WorkflowExecutor workflowExecutor, QueueDAO queueDAO) {
        this.metadataService = metadataService
        this.workflowExecutionService = workflowExecutionService
        this.workflowExecutor = workflowExecutor
        this.queueDAO = queueDAO
    }

    /**
     * This function registers all the taskDefinitions required to enable spock based integration testing
     */
    @PostConstruct
    def void taskDefinitions() {
        WorkflowContext.set(new WorkflowContext("integration_app"))

        (0..20).collect { "integration_task_$it" }
                .findAll { !getPersistedTaskDefinition(it).isPresent() }
                .collect { new TaskDef(it, it, 1, 120) }
                .forEach { metadataService.registerTaskDef([it]) }

        (0..4).collect { "integration_task_0_RT_$it" }
                .findAll { !getPersistedTaskDefinition(it).isPresent() }
                .collect { new TaskDef(it, it, 0, 120) }
                .forEach { metadataService.registerTaskDef([it]) }

        metadataService.registerTaskDef([new TaskDef('short_time_out', 'short_time_out', 1, 5)])

        //This task is required by the integration test which exercises the response time out scenarios
        TaskDef task = new TaskDef()
        task.name = "task_rt"
        task.timeoutSeconds = 120
        task.retryCount = RETRY_COUNT
        task.retryDelaySeconds = 0
        task.responseTimeoutSeconds = 10
        metadataService.registerTaskDef([task])
    }

    /**
     * This is an helper method that enables each test feature to run from a clean state
     * This method is intended to be used in the cleanup() or cleanupSpec() method of any spock specification.
     * By invoking this method all the running workflows are terminated.
     * @throws Exception When unable to terminate any running workflow
     */
    def void clearWorkflows() throws Exception {
        List<String> workflowsWithVersion = metadataService.getWorkflowDefs()
                .collect { workflowDef -> workflowDef.getName() + ":" + workflowDef.getVersion() }
        for (String workflowWithVersion : workflowsWithVersion) {
            String workflowName = StringUtils.substringBefore(workflowWithVersion, ":")
            int version = Integer.parseInt(StringUtils.substringAfter(workflowWithVersion, ":"))
            List<String> running = workflowExecutionService.getRunningWorkflows(workflowName, version)
            for (String workflowId : running) {
                Workflow workflow = workflowExecutor.getWorkflow(workflowId, false)
                if (!workflow.getStatus().isTerminal()) {
                    workflowExecutor.terminateWorkflow(workflowId, "cleanup")
                }
            }
        }

        queueDAO.queuesDetail().keySet()
                .forEach { queueDAO.flush(it) }

        new FileOutputStream(this.getClass().getResource(TEMP_FILE_PATH).getPath()).close();
    }

    /**
     * A helper method to retrieve a task definition that is persisted
     * @param taskDefName The name of the task for which the task definition is requested
     * @return an Optional of the TaskDefinition
     */
    def Optional<TaskDef> getPersistedTaskDefinition(String taskDefName) {
        try {
            return Optional.of(metadataService.getTaskDef(taskDefName))
        } catch (ApplicationException applicationException) {
            if (applicationException.code == ApplicationException.Code.NOT_FOUND) {
                return Optional.empty()
            } else {
                throw applicationException
            }
        }
    }

    /**
     * A helper methods that registers that workflows based on the paths of the json file representing a workflow definition
     * @param workflowJsonPaths a comma separated var ags of the paths of the workflow definitions
     */
    def void registerWorkflows(String... workflowJsonPaths) {
        workflowJsonPaths.collect { JsonUtils.fromJson(it, WorkflowDef.class) }
                .forEach { metadataService.updateWorkflowDef(it) }
    }

    /**
     * A helper method intended to be used in the <tt>when:</tt> block of the spock test feature
     * This method is intended to be used to poll and update the task status as failed
     * It also provides a delay to return if needed after the task has been updated to failed
     * @param taskName name of the task that needs to be polled and failed
     * @param workerId name of the worker id using which a task is polled
     * @param failureReason the reason to fail the task that will added to the task update
     * @param waitAtEndSeconds an optional delay before the method returns, if the value is 0 skips the delay
     * @return A Tuple of polledTask and acknowledgement of the poll
     */
    def Tuple pollAndFailTask(String taskName, String workerId, String failureReason, int waitAtEndSeconds) {
        def polledIntegrationTask = workflowExecutionService.poll(taskName, workerId)
        def ackPolledIntegrationTask = workflowExecutionService.ackTaskReceived(polledIntegrationTask.taskId)
        polledIntegrationTask.status = Task.Status.FAILED
        polledIntegrationTask.reasonForIncompletion = failureReason
        workflowExecutionService.updateTask(polledIntegrationTask)
        return waitAtEndSecondsAndReturn(waitAtEndSeconds, polledIntegrationTask, ackPolledIntegrationTask)
    }

    /**
     * A helper method to introduce delay and convert the polledIntegrationTask and ackPolledIntegrationTask
     * into a tuple. This method is intended to be used by pollAndFailTask and pollAndCompleteTask
     * @param waitAtEndSeconds The total seconds of delay before the method returns
     * @param polledIntegrationTask  instance of polled task
     * @param ackPolledIntegrationTask a acknowledgement of a poll
     * @return A Tuple of polledTask and acknowledgement of the poll
     */
    static def Tuple waitAtEndSecondsAndReturn(int waitAtEndSeconds, Task polledIntegrationTask, boolean ackPolledIntegrationTask) {
        if (waitAtEndSeconds > 0) {
            Thread.sleep(waitAtEndSeconds * 1000)
        }
        return new Tuple(polledIntegrationTask, ackPolledIntegrationTask)
    }

    /**
     * A helper method intended to be used in the <tt>when:</tt> block of the spock test feature
     * This method is intended to be used to poll and update the task status as completed
     * It also provides a delay to return if needed after the task has been updated to completed
     * @param taskName name of the task that needs to be polled and completed
     * @param workerId name of the worker id using which a task is polled
     * @param outputParams An optional output parameters if available will be added to the task before updating to completed
     * @param waitAtEndSeconds waitAtEndSeconds an optional delay before the method returns, if the value is 0 skips the delay
     * @return A Tuple of polledTask and acknowledgement of the poll
     */
    def Tuple pollAndCompleteTask(String taskName, String workerId, Map<String, String> outputParams, int waitAtEndSeconds) {
        def polledIntegrationTask = workflowExecutionService.poll(taskName, workerId)
        def ackPolledIntegrationTask = workflowExecutionService.ackTaskReceived(polledIntegrationTask.taskId)
        polledIntegrationTask.status = Task.Status.COMPLETED
        if (outputParams) {
            outputParams.forEach { k, v ->
                polledIntegrationTask.outputData[k] = v
            }
        }
        workflowExecutionService.updateTask(polledIntegrationTask)
        return waitAtEndSecondsAndReturn(waitAtEndSeconds, polledIntegrationTask, ackPolledIntegrationTask)
    }

    /**
     * A helper method intended to be used in the <tt>then:</tt> block of the spock test feature, ideally intended to be called after either:
     * pollAndCompleteTask function or  pollAndFailTask function
     * @param expectedTaskInputParams a map of input params that are verified against the polledTask that is part of the completedTaskAndAck tuple
     * @param completedTaskAndAck A Tuple of polledTask and acknowledgement of the poll
     */
    static def void verifyPolledAndAcknowledgedTask(Map<String, String> expectedTaskInputParams, Tuple completedTaskAndAck) {
        assert completedTaskAndAck[0] : "The task polled cannot be null"
        def polledIntegrationTask = completedTaskAndAck[0] as Task
        def ackPolledIntegrationTask = completedTaskAndAck[1] as boolean
        assert polledIntegrationTask
        assert ackPolledIntegrationTask
        if (expectedTaskInputParams) {
            expectedTaskInputParams.forEach {
                k, v ->
                    assert polledIntegrationTask.inputData.containsKey(k)
                    assert polledIntegrationTask.inputData[k] == v
            }
        }
    }

}
