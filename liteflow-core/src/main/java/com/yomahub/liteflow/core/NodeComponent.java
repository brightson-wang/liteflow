/**
 * <p>Title: liteflow</p>
 * <p>Description: 轻量级的组件式流程框架</p>
 * @author Bryan.Zhang
 * @email weenyc31@163.com
 * @Date 2020/4/1
 */
package com.yomahub.liteflow.core;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.ttl.TransmittableThreadLocal;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.executor.NodeExecutor;
import com.yomahub.liteflow.flow.executor.DefaultNodeExecutor;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.spi.holder.CmpAroundAspectHolder;
import com.yomahub.liteflow.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yomahub.liteflow.flow.entity.CmpStep;
import com.yomahub.liteflow.enums.CmpStepTypeEnum;
import com.yomahub.liteflow.slot.DataBus;
import com.yomahub.liteflow.slot.Slot;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.monitor.CompStatistics;
import com.yomahub.liteflow.monitor.MonitorBus;

import java.util.Map;

/**
 * 普通组件抽象类
 * @author Bryan.Zhang
 */
public abstract class NodeComponent{

	private final Logger LOG = LoggerFactory.getLogger(this.getClass());

	private MonitorBus monitorBus;

	private String nodeId;

	private String name;

	private NodeTypeEnum type;

	//这是自己的实例，取代this
	//为何要设置这个，用this不行么，因为如果有aop去切的话，this在spring的aop里是切不到的。self对象有可能是代理过的对象
	private NodeComponent self;

	//重试次数
	private int retryCount = 0;

	//在目标异常抛出时才重试
	private Class<? extends Exception>[] retryForExceptions = new Class[]{Exception.class};

	/** 节点执行器的类全名 */
	private Class<? extends NodeExecutor> nodeExecutorClass = DefaultNodeExecutor.class;

	/********************以下的属性为线程附加属性，并非不变属性********************/

	//当前slot的index
	private final TransmittableThreadLocal<Integer> slotIndexTL = new TransmittableThreadLocal<>();

	//是否结束整个流程，这个只对串行流程有效，并行流程无效
	private final TransmittableThreadLocal<Boolean> isEndTL = new TransmittableThreadLocal<>();

	//tag标签
	private final TransmittableThreadLocal<String> tagTL = new TransmittableThreadLocal<>();

	//当前流程名字
	private final TransmittableThreadLocal<String> currChainNameTL = new TransmittableThreadLocal<>();

	//组件外部参数
	private final TransmittableThreadLocal<String> cmpDataTL = new TransmittableThreadLocal<>();

	public NodeComponent() {
	}

	public void execute() throws Exception{
		Slot slot = this.getSlot();

		//在元数据里加入step信息
		CmpStep cmpStep = new CmpStep(nodeId, name, CmpStepTypeEnum.SINGLE);
		cmpStep.setTag(tagTL.get());
		slot.addStep(cmpStep);

		StopWatch stopWatch = new StopWatch();
		stopWatch.start();


		try{
			//前置处理
			self.beforeProcess(this.getNodeId(), slot);

			//主要的处理逻辑
			self.process();

			//成功后回调方法
			self.onSuccess();

			//步骤状态设为true
			cmpStep.setSuccess(true);
		} catch (Exception e){
			//步骤状态设为false，并加入异常
			cmpStep.setSuccess(false);
			cmpStep.setException(e);

			//执行失败后回调方法
			//这里要注意，失败方法本身抛出错误，只打出堆栈，往外抛出的还是主要的异常
			try{
				self.onError();
			}catch (Exception ex){
				String errMsg = StrUtil.format("[{}]:component[{}] onError method happens exception",slot.getRequestId(),this.getDisplayName());
				LOG.error(errMsg);
			}
			throw e;
		} finally {
			//后置处理
			self.afterProcess(this.getNodeId(), slot);

			stopWatch.stop();
			final long timeSpent = stopWatch.getTotalTimeMillis();
			LOG.debug("[{}]:component[{}] finished in {} milliseconds",slot.getRequestId(),this.getDisplayName(),timeSpent);

			//往CmpStep中放入时间消耗信息
			cmpStep.setTimeSpent(timeSpent);

			// 性能统计
			if (ObjectUtil.isNotNull(monitorBus)) {
				CompStatistics statistics = new CompStatistics(this.getClass().getSimpleName(), timeSpent);
				monitorBus.addStatistics(statistics);
			}
		}
	}

	public <T> void beforeProcess(String nodeId, Slot slot){
		//全局切面只在spring体系下生效，这里用了spi机制取到相应环境下的实现类
		//非spring环境下，全局切面为空实现
		CmpAroundAspectHolder.loadCmpAroundAspect().beforeProcess(nodeId, slot);
	}

	public abstract void process() throws Exception;

	public void onSuccess() throws Exception{
		//如果需要在成功后回调某一个方法，请覆盖这个方法
	}

	public void onError() throws Exception{
		//如果需要在抛错后回调某一段逻辑，请覆盖这个方法
	}

	public <T> void afterProcess(String nodeId, Slot slot){
		CmpAroundAspectHolder.loadCmpAroundAspect().afterProcess(nodeId, slot);
	}

	//是否进入该节点
	public boolean isAccess(){
		return true;
	}

	//出错是否继续执行(这个只适用于并行流程，串行流程不起作用)
	public boolean isContinueOnError() {
		return false;
	}

	//是否结束整个流程(不往下继续执行)
	public boolean isEnd() {
		Boolean isEnd = isEndTL.get();
		if(ObjectUtil.isNull(isEnd)){
			return false;
		} else {
			return isEndTL.get();
		}
	}

	//设置是否结束整个流程
	public void setIsEnd(boolean isEnd){
		this.isEndTL.set(isEnd);
	}

	public void removeIsEnd(){
		this.isEndTL.remove();
	}

	public NodeComponent setSlotIndex(Integer slotIndex) {
		this.slotIndexTL.set(slotIndex);
		return this;
	}

	public Integer getSlotIndex() {
		return this.slotIndexTL.get();
	}

	public void removeSlotIndex(){
		this.slotIndexTL.remove();
	}

	public Slot getSlot(){
		return DataBus.getSlot(this.slotIndexTL.get());
	}

	public <T> T getFirstContextBean(){
		return this.getSlot().getFirstContextBean();
	}

	public <T> T getContextBean(Class<T> contextBeanClazz){
		return this.getSlot().getContextBean(contextBeanClazz);
	}

	public String getNodeId() {
		return nodeId;
	}

	public void setNodeId(String nodeId) {
		this.nodeId = nodeId;
	}

	public NodeComponent getSelf() {
		return self;
	}

	public void setSelf(NodeComponent self) {
		this.self = self;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public NodeTypeEnum getType() {
		return type;
	}

	public void setType(NodeTypeEnum type) {
		this.type = type;
	}

	public <T> void sendPrivateDeliveryData(String nodeId, T t){
		this.getSlot().setPrivateDeliveryData(nodeId, t);
	}

	public <T> T getPrivateDeliveryData(){
		return this.getSlot().getPrivateDeliveryData(this.getNodeId());
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public Class<? extends Exception>[] getRetryForExceptions() {
		return retryForExceptions;
	}

	public void setRetryForExceptions(Class<? extends Exception>[] retryForExceptions) {
		this.retryForExceptions = retryForExceptions;
	}

	public Class<? extends NodeExecutor> getNodeExecutorClass() {
		return nodeExecutorClass;
	}

	public void setNodeExecutorClass(Class<? extends NodeExecutor> nodeExecutorClass) {
		this.nodeExecutorClass = nodeExecutorClass;
	}

	public void setTag(String tag){
		this.tagTL.set(tag);
	}

	public String getTag(){
		return this.tagTL.get();
	}

	public void removeTag(){
		this.tagTL.remove();
	}

	public MonitorBus getMonitorBus() {
		return monitorBus;
	}

	public void setMonitorBus(MonitorBus monitorBus) {
		this.monitorBus = monitorBus;
	}

	public <T> T getRequestData(){
		return getSlot().getRequestData();
	}

	public <T> T getSubChainReqData(){
		return getSlot().getChainReqData(this.getCurrChainName());
	}

	public <T> T getSubChainReqDataInAsync(){
		return getSlot().getChainReqDataFromQueue(this.getCurrChainName());
	}

	/**
	 * @deprecated 请使用 {@link #getChainId()}
	 * @return String
	 */
	@Deprecated
	public String getChainName(){
		return getSlot().getChainName();
	}
	
	public String getChainId(){
		return getSlot().getChainId();
	}

	public String getDisplayName(){
		if(StrUtil.isEmpty(this.name)){
			return this.nodeId;
		}else {
			return StrUtil.format("{}({})", this.nodeId, this.name);
		}
	}

	/**
	 * 
	 * @param currChainName 当前chain名称
	 * @deprecated 请使用 {@link #setCurrChainId(String)}
	 */
	@Deprecated
	public void setCurrChainName(String currChainName){
		this.currChainNameTL.set(currChainName);
	}

	/**
	 * @deprecated 请使用 {@link #getCurrChainId()}
	 * @return String
	 */
	@Deprecated
	public String getCurrChainName(){
		return this.currChainNameTL.get();
	}

	/**
	 * @deprecated 请使用 {@link #removeCurrChainId()}
	 */
	@Deprecated
	public void removeCurrChainName(){
		this.currChainNameTL.remove();
	}
	
	public void setCurrChainId(String currChainName){
		this.currChainNameTL.set(currChainName);
	}

	public String getCurrChainId(){
		return this.currChainNameTL.get();
	}

	public void removeCurrChainId(){
		this.currChainNameTL.remove();
	}

	public void setCmpData(String cmpData){
		this.cmpDataTL.set(cmpData);
	}

	public <T> T getCmpData(Class<T> clazz){
		if (StrUtil.isBlank(this.cmpDataTL.get())){
			return null;
		}
		if (clazz.equals(String.class)){
			return (T) this.cmpDataTL.get();
		}
		return JsonUtil.parseObject(this.cmpDataTL.get(), clazz);
	}

	public void removeCmpData(){
		this.cmpDataTL.remove();
	}

	public void invoke(String chainId, Object param) throws Exception {
		FlowExecutorHolder.loadInstance().invoke(chainId, param, this.getSlotIndex());
	}

	public LiteflowResponse invoke2Resp(String chainId, Object param) {
		return FlowExecutorHolder.loadInstance().invoke2Resp(chainId, param, this.getSlotIndex());
	}

	public void invokeInAsync(String chainId, Object param) throws Exception {
		FlowExecutorHolder.loadInstance().invokeInAsync(chainId, param, this.getSlotIndex());
	}

	public LiteflowResponse invoke2RespInAsync(String chainId, Object param) {
		return FlowExecutorHolder.loadInstance().invoke2RespInAsync(chainId, param, this.getSlotIndex());
	}
}
