// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.usage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.command.admin.usage.GenerateUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.GetUsageRecordsCmd;
import org.apache.cloudstack.api.response.UsageTypeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.usage.UsageService;
import org.apache.cloudstack.usage.UsageTypes;

import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.usage.dao.UsageDao;
import com.cloud.usage.dao.UsageJobDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.Manager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;

@Component
@Local(value = { UsageService.class })
public class UsageServiceImpl extends ManagerBase implements UsageService, Manager {
    public static final Logger s_logger = Logger.getLogger(UsageServiceImpl.class);
    
    //ToDo: Move implementation to ManagaerImpl
    
    @Inject private AccountDao _accountDao;
    @Inject private DomainDao _domainDao;
    @Inject private UsageDao _usageDao;
    @Inject private UsageJobDao _usageJobDao;
    @Inject private ConfigurationDao _configDao;
    @Inject private ProjectManager _projectMgr;
    private TimeZone _usageTimezone;

    public UsageServiceImpl() {
    }
    
    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
    	super.configure(name,  params);
        String timeZoneStr = _configDao.getValue(Config.UsageAggregationTimezone.toString());
        if (timeZoneStr == null) {
           timeZoneStr = "GMT";
        }
        _usageTimezone = TimeZone.getTimeZone(timeZoneStr);
        return true;
    }

    @Override
    public boolean generateUsageRecords(GenerateUsageRecordsCmd cmd) {
        Transaction txn = Transaction.open(Transaction.USAGE_DB);
        try {
            UsageJobVO immediateJob = _usageJobDao.getNextImmediateJob();
            if (immediateJob == null) {
                UsageJobVO job = _usageJobDao.getLastJob();

                String host = null;
                int pid = 0;
                if (job != null) {
                    host = job.getHost();
                    pid = ((job.getPid() == null) ? 0 : job.getPid().intValue());
                }
                _usageJobDao.createNewJob(host, pid, UsageJobVO.JOB_TYPE_SINGLE);
            }
        } finally {
            txn.close();

            // switch back to VMOPS_DB
            Transaction swap = Transaction.open(Transaction.CLOUD_DB);
            swap.close();
        }
        return true;
    }

    @Override
    public List<UsageVO> getUsageRecords(GetUsageRecordsCmd cmd) {
        Long accountId = cmd.getAccountId();
        Long domainId = cmd.getDomainId();
        String accountName = cmd.getAccountName();
        Account userAccount = null;
        Account caller = (Account)CallContext.current().getCallingAccount();
        Long usageType = cmd.getUsageType();
        Long projectId = cmd.getProjectId();
        
        if (projectId != null) {
            if (accountId != null) {
                throw new InvalidParameterValueException("Projectid and accountId can't be specified together");
            }
            Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id " + projectId);
            }
            accountId = project.getProjectAccountId();
        }
        
        //if accountId is not specified, use accountName and domainId
        if ((accountId == null) && (accountName != null) && (domainId != null)) {
            if (_domainDao.isChildDomain(caller.getDomainId(), domainId)) {
                Filter filter = new Filter(AccountVO.class, "id", Boolean.FALSE, null, null);
                List<AccountVO> accounts = _accountDao.listAccounts(accountName, domainId, filter); 
                if(accounts.size() > 0){
                    userAccount = accounts.get(0);
                }
                if (userAccount != null) {
                    accountId = userAccount.getId();
                } else {
                    throw new InvalidParameterValueException("Unable to find account " + accountName + " in domain " + domainId);
                }
            } else {
                throw new PermissionDeniedException("Invalid Domain Id or Account");
            }
        } 

        boolean isAdmin = false;
        
        //If accountId couldn't be found using accountName and domainId, get it from userContext
        if(accountId == null){
            accountId = caller.getId();
            //List records for all the accounts if the caller account is of type admin. 
            //If account_id or account_name is explicitly mentioned, list records for the specified account only even if the caller is of type admin
            if(caller.getType() == Account.ACCOUNT_TYPE_ADMIN){
                isAdmin = true;
            }
            s_logger.debug("Account details not available. Using userContext accountId: " + accountId);
        }

        Date startDate = cmd.getStartDate();
        Date endDate = cmd.getEndDate();
        if(startDate.after(endDate)){
        	throw new InvalidParameterValueException("Incorrect Date Range. Start date: "+startDate+" is after end date:"+endDate);
        }
        TimeZone usageTZ = getUsageTimezone();
        Date adjustedStartDate = computeAdjustedTime(startDate, usageTZ, true);
        Date adjustedEndDate = computeAdjustedTime(endDate, usageTZ, false);

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("getting usage records for account: " + accountId + ", domainId: " + domainId + ", between " + startDate + " and " + endDate + ", using pageSize: " + cmd.getPageSizeVal() + " and startIndex: " + cmd.getStartIndex());
        }

        Filter usageFilter = new Filter(UsageVO.class, "startDate", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        
        SearchCriteria<UsageVO> sc = _usageDao.createSearchCriteria();

        if (accountId != -1 && accountId != Account.ACCOUNT_ID_SYSTEM && !isAdmin) {
            sc.addAnd("accountId", SearchCriteria.Op.EQ, accountId);
        }

        if (domainId != null) {
            sc.addAnd("domainId", SearchCriteria.Op.EQ, domainId);
        }
        
        if (usageType != null) {
            sc.addAnd("usageType", SearchCriteria.Op.EQ, usageType);
        }

        if ((adjustedStartDate != null) && (adjustedEndDate != null) && adjustedStartDate.before(adjustedEndDate)) {
            sc.addAnd("startDate", SearchCriteria.Op.BETWEEN, adjustedStartDate, adjustedEndDate);
            sc.addAnd("endDate", SearchCriteria.Op.BETWEEN, adjustedStartDate, adjustedEndDate);
        } else {
            return new ArrayList<UsageVO>(); // return an empty list if we fail to validate the dates
        }

        List<UsageVO> usageRecords = null;
        Transaction txn = Transaction.open(Transaction.USAGE_DB);
        try {
            usageRecords = _usageDao.searchAllRecords(sc, usageFilter);
        } finally {
            txn.close();

            // switch back to VMOPS_DB
            Transaction swap = Transaction.open(Transaction.CLOUD_DB);
            swap.close();
        }

        return usageRecords;
    }

    @Override
    public TimeZone getUsageTimezone() {
        return _usageTimezone;
    }

    private Date computeAdjustedTime(Date initialDate, TimeZone targetTZ, boolean adjustToDayStart) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(initialDate);
        TimeZone localTZ = cal.getTimeZone();
        int timezoneOffset = cal.get(Calendar.ZONE_OFFSET);
        if (localTZ.inDaylightTime(initialDate)) {
            timezoneOffset += (60 * 60 * 1000);
        }
        cal.add(Calendar.MILLISECOND, timezoneOffset);

        Date newTime = cal.getTime();

        Calendar calTS = Calendar.getInstance(targetTZ);
        calTS.setTime(newTime);
        timezoneOffset = calTS.get(Calendar.ZONE_OFFSET);
        if (targetTZ.inDaylightTime(initialDate)) {
            timezoneOffset += (60 * 60 * 1000);
        }

        calTS.add(Calendar.MILLISECOND, -1*timezoneOffset);
        if (adjustToDayStart) {
            calTS.set(Calendar.HOUR_OF_DAY, 0);
            calTS.set(Calendar.MINUTE, 0);
            calTS.set(Calendar.SECOND, 0);
            calTS.set(Calendar.MILLISECOND, 0);
        } else {
            calTS.set(Calendar.HOUR_OF_DAY, 23);
            calTS.set(Calendar.MINUTE, 59);
            calTS.set(Calendar.SECOND, 59);
            calTS.set(Calendar.MILLISECOND, 999);
        }

        return calTS.getTime();
    }

	@Override
	public List<UsageTypeResponse> listUsageTypes() {
		return UsageTypes.listUsageTypes();
	}


}
