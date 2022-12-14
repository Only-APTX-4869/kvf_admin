package com.kalvin.kvf.modules.workflow.service.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kalvin.kvf.common.exception.KvfException;
import com.kalvin.kvf.common.utils.ShiroKit;
import com.kalvin.kvf.common.utils.SpringContextKit;
import com.kalvin.kvf.modules.workflow.dto.FlowData;
import com.kalvin.kvf.modules.workflow.dto.NodeAssignee;
import com.kalvin.kvf.modules.workflow.dto.ProcessNode;
import com.kalvin.kvf.modules.workflow.entity.Form;
import com.kalvin.kvf.modules.workflow.entity.ProcessForm;
import com.kalvin.kvf.modules.workflow.service.FormService;
import com.kalvin.kvf.modules.workflow.service.ProcessFormService;
import com.kalvin.kvf.modules.workflow.utils.ProcessKit;
import com.kalvin.kvf.modules.workflow.vo.FormConfigVO;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.UserTask;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.impl.identity.Authentication;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.apache.velocity.runtime.directive.contrib.For;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Create by Kalvin on 2020/4/20.
 */
@Slf4j
@Service("myProcessEngine")
public class ProcessEngineImpl implements IProcessEngine {

    @Resource
    private RepositoryService repositoryService;

    @Resource
    private RuntimeService runtimeService;

    @Resource
    private TaskService taskService;

    @Resource
    private HistoryService historyService;

    @Resource
    private FormService formService;

    @Resource
    private ProcessFormService processFormService;

    @Override
    public String start(String deploymentId) {
        return this.start(deploymentId, null, null);
    }

    @Override
    public String start(String deploymentId, String businessId) {
        return this.start(deploymentId, businessId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public String start(String deploymentId, String businessId, String startUser) {
        // ??????????????????????????????
        final HashMap<String, Object> taskVariables = new HashMap<>();
        final FlowData flowData = new FlowData();

        if (StrUtil.isBlank(deploymentId)) {
            throw new KvfException("????????????ID???????????????");
        }
        if (StrUtil.isBlank(startUser)) {
            // ????????????????????????
            startUser = ShiroKit.getUser().getUsername();
        }

        ProcessForm processForm = processFormService.getByModelId(ProcessKit.getModel(deploymentId).getId());
        if (processForm == null) {
            throw new KvfException("???????????????????????????????????????????????????");
        }

        flowData.setMainFormKey(processForm.getFormCode());
        flowData.setStartUser(startUser);
        flowData.setCurrentUser(startUser);
        flowData.setFirstNode(true);
        flowData.setFirstSubmit(true);
        flowData.setNextUser(startUser);
        taskVariables.put(ProcessKit.FLOW_DATA_KEY, flowData);

        // ??????????????????????????????  // TODO: 2020/4/22 ?????????????????????
        Authentication.setAuthenticatedUserId(startUser);

        ProcessDefinition processDefinition = ProcessKit.getProcessDefinition(deploymentId);
        if (processDefinition.isSuspended()) {
            throw new KvfException("?????????????????????????????????????????????????????????");
        }
        String processDefinitionId = processDefinition.getId();

        // ????????????
        Form form = formService.getByCode(flowData.getMainFormKey());
        if (Objects.isNull(form)) {
            throw new KvfException("???????????????????????????????????????");
        }
        taskVariables.put(ProcessKit.FORM_KEY, form);

        ProcessInstance processInstance;
        if (StrUtil.isBlank(businessId)) {
            processInstance =  runtimeService.startProcessInstanceById(processDefinitionId, taskVariables);
        } else {
            // TODO: 2020/4/22 ???????????????????????????
            processInstance =  runtimeService.startProcessInstanceById(processDefinitionId, businessId, taskVariables);
        }

        // ??????????????????????????????????????????
        String processName = processDefinition.getName();
        String processInstanceId = processInstance.getProcessInstanceId();
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        String taskId = task.getId();

        // ????????????????????????
        runtimeService.setProcessInstanceName(processInstanceId, processName);

        flowData.setProcessName(processName);
        flowData.setProcessDefinitionId(processDefinitionId);
        flowData.setProcessInstanceId(processInstanceId);
        flowData.setFirstNodeId(task.getTaskDefinitionKey());
        flowData.setCurrentNodeId(task.getTaskDefinitionKey());
        flowData.setCurrentNodeName(task.getName());
        flowData.setTaskId(taskId);
        flowData.setExecutionId(task.getExecutionId());
        // ??????????????????????????????
        taskVariables.put(ProcessKit.FLOW_DATA_KEY, flowData);
        // ??????????????????????????????
        taskVariables.put(ProcessKit.FLOW_VARIABLES_KEY, new HashMap<>());
        taskService.setVariables(taskId, taskVariables);
        log.debug("?????????{}??????????????????{}????????????{}???", startUser, processName, processInstance.getId());
        return taskId;
    }

    @Override
    public void submitTask(Map<String, Object> flowVariables) {
        final Map<String, Object> variables = new HashMap<>(flowVariables);
        final String currentUser = ShiroKit.getUser().getUsername();
        final FlowData flowData = new FlowData();
        BeanUtil.copyProperties(flowVariables, flowData);

        // ?????????????????????
        final String nextUser = flowData.getNextUser();
        final String comment = StrUtil.isBlank(flowData.getComment()) ? ProcessKit.DEFAULT_AGREE_COMMENT : flowData.getComment();
        final String taskId = flowData.getTaskId();
        final String processInstanceId = flowData.getProcessInstanceId();
        final int nextNodeNum = flowData.getNextNodeNum();
        final Task currentTask = ProcessKit.getCurrentTask(taskId);
        final String currentNodeId = currentTask.getTaskDefinitionKey();

        if (StrUtil.isBlank(processInstanceId)) {
            throw new KvfException("????????????ID???????????????");
        }
        if (StrUtil.isBlank(nextUser) && nextNodeNum != 0) {
            throw new KvfException("????????????????????????????????????");
        }

        if (flowData.isFirstSubmit()) {
            flowData.setFirstSubmitTime(new Date());
            String tableData = flowData.getTableData();
            if (StrUtil.isNotBlank(tableData)) {
                JSONArray objects = JSONUtil.parseArray(tableData);
                IService service = SpringContextKit.getBean(flowData.getServiceBean());
                List collect = objects.stream().map(m -> {
                    JSONObject mObj = (JSONObject) m;
                    mObj.put(ProcessKit.FLOW_INST_ID, processInstanceId);
                    Object o = ReflectUtil.newInstance(flowData.getEntityClazz());
                    BeanUtil.copyProperties(mObj, o);
                    return o;
                }).collect(Collectors.toList());
                service.saveOrUpdateBatch(collect);
            }
        }
        flowData.setFirstNode(false);
        flowData.setFirstSubmit(false);

        // ???????????????????????????????????????
        HashMap<String, NodeAssignee> nodeAssignee = flowData.getNodeAssignee();
        if (nodeAssignee == null) {
            nodeAssignee = new HashMap<>();
        }
        nodeAssignee.put(currentNodeId + "_" + taskId, new NodeAssignee(taskId, currentNodeId, currentUser));
        flowData.setNodeAssignee(nodeAssignee);

        variables.put(ProcessKit.FLOW_VARIABLES_KEY, flowVariables);
        variables.put(ProcessKit.FLOW_DATA_KEY, flowData);

        if (nextNodeNum == 0 || nextNodeNum == 1) {
            // ??????????????????
            // ??????????????????
            taskService.addComment(taskId, processInstanceId, comment);

            // TODO: owner???????????????????????????????????????
            /*if (StrUtil.isNotBlank(currentTask.getOwner())) {
                DelegationState delegationState = currentTask.getDelegationState();
                // ?????????????????????????????????????????????????????????
                if (DelegationState.PENDING == delegationState) {
                    taskService.resolveTask(currentTask.getId());
                }
            }*/

             Authentication.setAuthenticatedUserId(nextUser);

            // ???????????????????????????????????????????????????????????????????????????????????????????????????activiti6???bug?????????
            taskService.setAssignee(taskId, "");
            taskService.setAssignee(taskId, currentUser);

            // ??????????????????
            taskService.complete(taskId, variables);
        } else if (nextNodeNum > 1) {
            ProcessKit.nodeJumpTo(taskId, flowData.getTargetNodeId(), currentUser, variables, comment);
        } else {
            throw new KvfException("??????????????????");
        }

    }

    @Override
    public void suspendProcessInstance(String processInstanceId) {
        runtimeService.suspendProcessInstanceById(processInstanceId);
    }

    @Override
    public void activateProcessInstance(String processInstanceId) {
        runtimeService.activateProcessInstanceById(processInstanceId);
    }

    @Override
    public void suspendProcessDefinitionByIds(List<String> deploymentIds) {
        deploymentIds.forEach(deploymentId -> {
            ProcessDefinition processDefinition = ProcessKit.getProcessDefinition(deploymentId);
            if (!processDefinition.isSuspended()) {
                repositoryService.suspendProcessDefinitionById(processDefinition.getId());
            }
        });
    }

    @Override
    public void activateProcessDefinitionByIds(List<String> deploymentIds) {
        deploymentIds.forEach(deploymentId -> {
            ProcessDefinition processDefinition = ProcessKit.getProcessDefinition(deploymentId);
            if (processDefinition.isSuspended()) {
                repositoryService.activateProcessDefinitionById(processDefinition.getId());
            }
        });
    }

    @Override
    public void withdrawApproval(String oldTaskId) {
        final String currentUser = ShiroKit.getUser().getUsername();
        HistoricTaskInstance historicTaskInstance = ProcessKit.getHistoricTaskInstance(oldTaskId);
        // ??????????????????
        final String targetNodeId = historicTaskInstance.getTaskDefinitionKey();
        final String processDefinitionId = historicTaskInstance.getProcessDefinitionId();
        final String processInstanceId = historicTaskInstance.getProcessInstanceId();

        if (ProcessKit.isFinished(processInstanceId)) {
            throw new KvfException("??????????????????????????????");
        }

        // ????????????????????????????????????????????????????????????
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        if (tasks.size() == 1) {
            Task currentTask = tasks.get(0);
            String currentTaskId = currentTask.getId();
            String currentNodeId = currentTask.getTaskDefinitionKey();

            final Map<String, Object> variables = taskService.getVariables(currentTaskId);

            // ??????????????????ID
            List<UserTask> nextNodes = ProcessKit.getNextNode(processDefinitionId, targetNodeId, variables);
            if (nextNodes.size() > 1) {
                throw new KvfException("?????????????????????????????????");
            }
            UserTask userTask = nextNodes.get(0);
            String expectNodeId = userTask.getId();
            if (currentNodeId.equals(expectNodeId)) {
                // ??????
                ProcessKit.setNextUser(currentUser, variables);
                ProcessKit.nodeJumpTo(currentTaskId, targetNodeId, variables, "??????");
                // ??????
//                activityMapper.deleteHisTaskInstanceByTaskId(currentTaskId);
//                activityMapper.deleteHisActivityInstanceByTaskId(currentTaskId);
            } else {
                throw new KvfException("?????????????????????????????????");
            }

        } else if (tasks.size() > 1) {
            // TODO: 2020/5/1 ?????????????????????
            throw new KvfException("?????????????????????????????????");
        } else {
            throw new KvfException("?????????????????????????????????????????????");
        }

    }

    @Override
    public Map<String, Object> getCurrentUserTaskVariables(String taskId) {
        final Map<String, Object> hashMap = new HashMap<>();
        final String currentUser = ShiroKit.getUser().getUsername();
        Task currentTask = ProcessKit.getCurrentUserTask(taskId);
        if (currentTask == null) {
            // TODO: ???????????????????????????????????????
            return null;
        }

        // ??????????????????????????????
        final Map<String, Object> variables = taskService.getVariables(taskId);
        final Map<String, Object> flowVariables = (Map<String, Object>) variables.get(ProcessKit.FLOW_VARIABLES_KEY);
        final FlowData flowData = (FlowData) variables.get(ProcessKit.FLOW_DATA_KEY);
        final Form form = (Form) variables.get(ProcessKit.FORM_KEY);

        /*
         * ????????????????????????????????????
         * ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
         */
        final String formKey = flowData.getMainFormKey();
        final String processDefinitionId = flowData.getProcessDefinitionId();
        final String currentNodeId = currentTask.getTaskDefinitionKey();
        final String currentExecutionId = currentTask.getExecutionId();
        final String currentNodeName = currentTask.getName();

        FormConfigVO formConfig = formService.getFormConfig(form, flowVariables);
        /*
         * ???????????????????????????????????????FlowData???
         */
        // ?????????????????????????????????
        if (!flowData.isFirstNode()) {
            flowData.setFirstNode(false);
        }
        flowData.setCurrentUser(currentUser);
        flowData.setCurrentNodeId(currentNodeId);
        flowData.setCurrentNodeName(currentNodeName);
        flowData.setTaskId(taskId);
        flowData.setExecutionId(currentExecutionId);

        hashMap.put(ProcessKit.FLOW_DATA_KEY, flowData);
        hashMap.put(ProcessKit.FORM_CONFIG_KEY, formConfig);

        // ????????????????????????????????????????????????
        List<UserTask> nextUserTask = ProcessKit.getNextNode(processDefinitionId, currentNodeId, flowVariables);
        List<ProcessNode> processNodes = ProcessKit.convertTo(nextUserTask);
        flowData.setNextNodes(processNodes);
        flowData.setNextNodeNum(processNodes.size());

        // ???????????????????????????????????????????????????
        List<ProcessNode> canBackNodes = ProcessKit.getCanBackNodes(currentNodeId, processDefinitionId);
        flowData.setCanBackNodes(canBackNodes);

        log.debug("hashMap={}", hashMap);
        return hashMap;
    }

    @Override
    public Map<String, Object> getHisUserTaskVariables(String processInstanceId) {
        final Map<String, Object> hashMap = new HashMap<>();

        // ??????????????????????????????
        final Map<String, Object> flowVariables = ProcessKit.getHisFlowVariables(processInstanceId);
        final FlowData flowData = ProcessKit.getHisFlowData(processInstanceId);
        final Form form = ProcessKit.getHisFromConfigData(processInstanceId);
        // ????????????
        flowData.setReadOnly(true);

        // ???????????????????????????????????????
        FormConfigVO formConfig = formService.getFormConfig(form, flowVariables);

        hashMap.put(ProcessKit.FLOW_DATA_KEY, flowData);
        hashMap.put(ProcessKit.FORM_CONFIG_KEY, formConfig);

        log.debug("his hashMap={}", hashMap);
        return hashMap;
    }

    @Override
    public void backToFirstNode(String taskId) {
        final String currentUser = ShiroKit.getUser().getUsername();
        Map<String, Object> variables = taskService.getVariables(taskId);
        FlowData flowData = ProcessKit.getFlowData(variables);
        flowData.setFirstNode(true);
        flowData.setNextUser(flowData.getStartUser());
        ProcessKit.nodeJumpTo(taskId, flowData.getFirstNodeId(), currentUser, variables, "???????????????");
    }

    @Override
    public void backToPreNode(String taskId) {
        final String currentUser = ShiroKit.getUser().getUsername();
        Map<String, Object> variables = taskService.getVariables(taskId);
        FlowData flowData = ProcessKit.getFlowData(variables);
        Task currentTask = ProcessKit.getCurrentTask(taskId);
        // ??????????????????
        ProcessNode preOneIncomeNode = ProcessKit.getPreOneIncomeNode(currentTask.getTaskDefinitionKey(), flowData.getProcessDefinitionId());
        if (preOneIncomeNode == null) {
            throw new KvfException("???????????????preOneIncomeNode???????????????");
        }
        String preNodeId = preOneIncomeNode.getNodeId();
        // ??????????????????
        if (preNodeId.equals(flowData.getFirstNodeId())) {
            flowData.setFirstNode(true);
            flowData.setNextUser(flowData.getStartUser());
        } else {
            // ???????????????????????????
            int taskType = ProcessKit.getTaskType(preNodeId, flowData.getProcessDefinitionId());
            if (taskType == ProcessKit.USER_TASK_TYPE_NORMAL) {
                HistoricActivityInstance historicActivityInstance = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(flowData.getProcessInstanceId()).activityId(preNodeId).finished().singleResult();
                String assignee = historicActivityInstance.getAssignee();
                flowData.setNextUser(assignee);
            } else {
                throw new KvfException("????????????????????????????????????");
            }
        }
        ProcessKit.nodeJumpTo(taskId, preNodeId, currentUser, variables, "???????????????");
    }

    @Override
    public void back2Node(String taskId, String targetNodeId) {
        final String currentUser = ShiroKit.getUser().getUsername();
        Map<String, Object> variables = taskService.getVariables(taskId);
        FlowData flowData = ProcessKit.getFlowData(variables);
        // ??????????????????
        if (targetNodeId.equals(flowData.getFirstNodeId())) {
            flowData.setFirstNode(true);
            flowData.setNextUser(flowData.getStartUser());
        } else {
            // ???????????????????????????
            int taskType = ProcessKit.getTaskType(targetNodeId, flowData.getProcessDefinitionId());
            if (taskType == ProcessKit.USER_TASK_TYPE_NORMAL) {
                HistoricActivityInstance historicActivityInstance = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(flowData.getProcessInstanceId()).activityId(targetNodeId).finished().singleResult();
                String assignee = historicActivityInstance.getAssignee();
                flowData.setNextUser(assignee);
            } else {
                throw new KvfException("????????????????????????????????????");
            }
        }
        FlowElement flowElement = ProcessKit.getFlowElement(targetNodeId, flowData.getProcessDefinitionId());
        ProcessKit.nodeJumpTo(taskId, targetNodeId, currentUser, variables, "?????????" + flowElement.getName() + "?????????");
    }
}
