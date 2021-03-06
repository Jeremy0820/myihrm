package com.ihrm.audit.service;

import com.alibaba.fastjson.JSON;
import com.ihrm.audit.client.FeignClientService;
import com.ihrm.audit.dao.ProcInstanceDao;
import com.ihrm.audit.dao.ProcTaskInstanceDao;
import com.ihrm.audit.dao.ProcUserGroupDao;
import com.ihrm.audit.entity.ProcInstance;
import com.ihrm.audit.entity.ProcTaskInstance;
import com.ihrm.audit.entity.ProcUserGroup;
import com.ihrm.common.utils.IdWorker;
import com.ihrm.domain.system.User;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.IdentityLink;
import org.activiti.engine.task.Task;
import org.hibernate.SQLQuery;
import org.hibernate.transform.Transformers;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.criteria.*;
import java.util.*;

/**
 * @author: hyl
 * @date: 2020/03/20
 **/
@Service
public class AuditService {

    @Autowired
    private ProcInstanceDao procInstanceDao;

    @Autowired
    private FeignClientService feignClientService;

    @Autowired
    private IdWorker idWorker;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcTaskInstanceDao procTaskInstanceDao;

    @Autowired
    private ProcUserGroupDao procUserGroupDao;

    @Autowired
    private EntityManager entityManager;

   /**
     * ?????????????????????????????????
     * @param instance  ????????????
     * @param page  ??????
     * @param size  ????????????
     * @return
     */
    public Page getInstanceList(ProcInstance instance, int page, int size) {
        //??????Specification????????????
        Specification<ProcInstance> spec = new Specification<ProcInstance>() {
            @Override
            public Predicate toPredicate(Root<ProcInstance> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
                List<Predicate> list = new ArrayList<>();
                //????????????
                if (!StringUtils.isEmpty(instance.getProcessKey())){
                    list.add(cb.equal(root.get("processKey").as(String.class) , instance.getProcessKey()));
                }
                //????????????
                if (!StringUtils.isEmpty(instance.getProcessState())){
                    Expression<String> exp =root.get("processState");
                    list.add(exp.in(instance.getProcessState().split(",")));
                }
                //???????????????????????????
                if (!StringUtils.isEmpty(instance.getProcCurrNodeUserId())){
                    list.add(cb.like(root.get("procCurrNodeUserId").as(String.class) , "%" + instance.getProcCurrNodeUserId() + "%"));
                }
                //????????? -- userId
                if(!StringUtils.isEmpty(instance.getUserId())) {
                    list.add(cb.equal(root.get("userId").as(String.class),instance.getUserId()));
                }
                return cb.and(list.toArray(new Predicate[list.size()]));
            }
        };
        //??????????????????
        //??????dao??????specification??????
        return procInstanceDao.findAll(spec , new PageRequest(page-1 , size));
    }

    /**
     * ??????id???????????????????????????
     * @param id    ????????????id
     * @return
     */
    public ProcInstance findInstanceDetail(String id) {
        return procInstanceDao.findById(id).get();
    }

    /**
     * ????????????
     * @param map
     * @param companyId
     */
    public void startProcess(Map map, String companyId) {
        //??????????????????
        String userId = (String) map.get("userId");
        String processKey = (String) map.get("processKey");
        String processName = (String) map.get("processName");

        User user = feignClientService.getUserInfoByUserId(userId);
        ProcInstance instance = new ProcInstance();
        BeanUtils.copyProperties(user , instance);

        instance.setUserId(userId);
        instance.setProcessId(idWorker.nextId()+"");
        instance.setProcApplyTime(new Date());
        instance.setProcessKey(processKey);
        instance.setProcessName(processName);
        instance.setProcessState("1");  //?????????
        String data = JSON.toJSONString(map);
        instance.setProcData(data);
        //??????????????????
        ProcessDefinition result = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .processDefinitionTenantId(companyId)
                .latestVersion()
                .singleResult();
        //????????????
        Map vars = new HashMap();
        if ("process_leave".equals(processKey)){
            //??????
            vars.put("days" , map.get("duration"));
        }
        runtimeService.startProcessInstanceById(result.getId()
                , instance.getProcessId()
                , vars);  //???????????????id,????????????id,???????????????
        ProcessInstance processInstance =
                runtimeService.startProcessInstanceById(result.getId(), instance.getProcessId(), vars);
        //?????????????????????????????????
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        taskService.complete(task.getId());
        //???????????????????????????,???????????????????????????????????????
        Task nextTask = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        if(nextTask != null) {
            List<User> users = findCurrUsers(nextTask, user);
            StringBuilder userNameSb = new StringBuilder();
            StringBuilder userIdS = new StringBuilder();
            for (User user1 : users) {
                userNameSb.append(user1.getUsername()).append(" ");
                userIdS.append(user1.getId());
            }
            instance.setProcCurrNodeUserId(userIdS.toString());
            instance.setProcCurrNodeUserName(userNameSb.toString());
        }

        procInstanceDao.save(instance);
        ProcTaskInstance pti = new ProcTaskInstance();
        pti.setTaskId(idWorker.nextId() + "");
        pti.setProcessId(instance.getProcessId());
        pti.setHandleTime(new Date());
        pti.setHandleType("2");   //????????????
        pti.setHandleUserId(userId);
        pti.setHandleUserName(user.getUsername());
        pti.setTaskKey(task.getTaskDefinitionKey());
        pti.setTaskName(task.getName());
        pti.setHandleOpinion("????????????");
        procTaskInstanceDao.save(pti);
    }

    private List<User> findCurrUsers(Task nextTask,User user) {
        //???????????????????????????(????????????)
        List<IdentityLink> list = taskService.getIdentityLinksForTask(nextTask.getId());
        List<User> users = new ArrayList<>();
        for (IdentityLink identityLink : list) {
            String groupId = identityLink.getGroupId(); //????????????
            ProcUserGroup userGroup = procUserGroupDao.findById(groupId).get(); //??????UserGroup
            String param = userGroup.getParam();
            String paramValue = null;
            if ("user_id".equals(param)) {
                paramValue = user.getId();
            }
            else if ("department_id".equals(param)) {
                paramValue = user.getDepartmentId();
            }
            else if ("company_id".equals(param)) {
                paramValue = user.getCompanyId();
            }
            String sql = userGroup.getIsql().replaceAll("\\$\\{" + param + "\\}", paramValue);
            Query query = entityManager.createNativeQuery(sql);
            query.unwrap(SQLQuery.class).setResultTransformer(Transformers.aliasToBean(User.class));
            users.addAll(query.getResultList());
        }
        return users;
    }

    /**
     * ????????????
     * @param taskInstance  ????????????
     * @param companyId ??????id
     */
    public void commit(ProcTaskInstance taskInstance, String companyId) {
        //????????????????????????
        String processId = taskInstance.getProcessId();
        ProcInstance instance = procInstanceDao.findById(processId).get();
        //????????????????????????
        instance.setProcessState(taskInstance.getHandleType());
        //???????????????????????????,???????????????????????????
        List<ProcessInstance> processInstanceList = runtimeService.createProcessInstanceQuery()
                                                        .processInstanceBusinessKey(processId)
                                                        .list();

        User user = feignClientService.getUserInfoByUserId(taskInstance.getHandleUserId());
        if ("2".equals(taskInstance.getHandleType())){
            //??????????????????,?????????????????????
            //?????????????????????,????????????????????????
            Task task = taskService.createTaskQuery().processInstanceId(processInstanceList.get(0).getId()).singleResult();
            taskService.complete(task.getId());
            //????????????????????????,???????????????????????????????????????
            Task nextTask = taskService.createTaskQuery().processInstanceId(processInstanceList.get(0).getId()).singleResult();
            if(nextTask != null) {
                List<User> users = findCurrUsers(nextTask, user);
                String usernames = "", userIdS = "";
                for (User user1 : users) {
                    usernames += user1.getUsername() + " ";
                    userIdS += user1.getId();
                }
                instance.setProcCurrNodeUserId(userIdS);
                instance.setProcCurrNodeUserName(usernames);
                instance.setProcessState("1");
            }else{
                //??????????????????????????????,????????????
                instance.setProcessState("2");
            }
        }else{
            //?????????????????????/??????,????????????
            runtimeService.deleteProcessInstance(processInstanceList.get(0).getId() , taskInstance.getHandleOpinion());
        }
        //????????????????????????,????????????????????????
        procInstanceDao.save(instance);
        taskInstance.setTaskId(idWorker.nextId() + "");
        taskInstance.setHandleUserName(user.getUsername());
        taskInstance.setHandleTime(new Date());
        procTaskInstanceDao.save(taskInstance);
    }

    public List<ProcTaskInstance> findTasksByProcess(String id) {
        return procTaskInstanceDao.findByProcessId(id);
    }
}
