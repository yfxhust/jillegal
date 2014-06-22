/**
 * @author SERKAN OZAL
 *         
 *         E-Mail: <a href="mailto:serkanozal86@hotmail.com">serkanozal86@hotmail.com</a>
 *         GitHub: <a>https://github.com/serkan-ozal</a>
 */

package tr.com.serkanozal.jillegal.offheap.pool.impl;

import tr.com.serkanozal.jillegal.offheap.domain.model.pool.StringOffHeapPoolCreateParameter;
import tr.com.serkanozal.jillegal.offheap.pool.DeeplyForkableStringOffHeapPool;
import tr.com.serkanozal.jillegal.offheap.pool.StringOffHeapPool;
import tr.com.serkanozal.jillegal.util.JvmUtil;

public class DefaultStringOffHeapPool extends BaseOffHeapPool<String, StringOffHeapPoolCreateParameter> 
		implements StringOffHeapPool, DeeplyForkableStringOffHeapPool {

	protected int estimatedStringCount;
	protected int estimatedStringLength;
	protected int charArrayIndexScale;
	protected int charArrayIndexStartOffset;
	protected int valueArrayOffsetInString;
	protected int stringSize;
	protected long allocationStartAddress;
	protected long allocationEndAddress;
	protected long allocationSize;
	protected long currentAddress;
	protected String sampleStr;
	protected long sampleStrAddress;
	protected char[] sampleCharArray;
	
	public DefaultStringOffHeapPool(StringOffHeapPoolCreateParameter parameter) {
		this(parameter.getEstimatedStringCount(), parameter.getEstimatedStringLength());
	}
	
	public DefaultStringOffHeapPool(int estimatedStringCount, int estimatedStringLength) {
		super(String.class);
		init(estimatedStringCount, estimatedStringLength);
	}
	
	protected void init(int estimatedStringCount, int estimatedStringLength) {
		this.estimatedStringCount = estimatedStringCount;
		this.estimatedStringLength = estimatedStringLength;
		init();
	}
	
	@SuppressWarnings({ "restriction", "deprecation" })
	protected void init() {
		try {
			charArrayIndexScale = JvmUtil.arrayIndexScale(char.class);
			charArrayIndexStartOffset = JvmUtil.arrayBaseOffset(char.class);
			valueArrayOffsetInString = JvmUtil.getUnsafe().fieldOffset(String.class.getDeclaredField("value"));
			stringSize = (int) JvmUtil.sizeOf(String.class);
			int estimatedStringSize = (int) (stringSize + JvmUtil.sizeOfArray(char.class, estimatedStringLength));
			allocationSize = (estimatedStringSize * estimatedStringCount) + JvmUtil.getAddressSize(); // Extra memory for possible aligning
			allocationStartAddress = directMemoryService.allocateMemory(allocationSize); 
			allocationEndAddress = allocationStartAddress + allocationSize;
			currentAddress = allocationStartAddress;
			sampleStr = new String();
			sampleStrAddress = JvmUtil.addressOf(sampleStr);
			sampleCharArray = new char[0];
		}
		catch (Throwable t) {
			logger.error("Error occured while initializing \"StringOffHeapPool\"", t);
			throw new IllegalStateException(t);
		}
	}
	
	@Override
	public Class<String> getElementType() {
		return String.class;
	}
	
	@Override
	public synchronized String get(String str) {
		return allocateStringFromOffHeap(str);
	}
	
	protected String allocateStringFromOffHeap(String str) {
		long addressOfStr = JvmUtil.addressOf(str);
		char[] valueArray = (char[]) directMemoryService.getObject(str, valueArrayOffsetInString);
		int valueArraySize = charArrayIndexStartOffset + (charArrayIndexScale * valueArray.length);
		int strSize = stringSize + valueArraySize + JvmUtil.getAddressSize(); // Extra memory for possible aligning
		
		long addressMod1 = currentAddress % JvmUtil.getAddressSize();
		if (addressMod1 != 0) {
			currentAddress += (JvmUtil.getAddressSize() - addressMod1);
		}
		
		if (currentAddress + strSize > allocationEndAddress) {
			return null;
		}
		
		// Copy string object content to allocated area
		directMemoryService.copyMemory(addressOfStr, currentAddress, strSize);
		
		long valueAddress = currentAddress + stringSize;
		long addressMod2 = valueAddress % JvmUtil.getAddressSize();
		if (addressMod2 != 0) {
			valueAddress += (JvmUtil.getAddressSize() - addressMod2);
		}
		
		// Copy value array in allocated string to allocated char array
		directMemoryService.copyMemory(
				JvmUtil.toNativeAddress(
						directMemoryService.getAddress(addressOfStr + valueArrayOffsetInString)),
				valueAddress, 
				valueArraySize);

		// Now, value array in allocated string points to allocated char array
		directMemoryService.putAddress(
				currentAddress + valueArrayOffsetInString, 
				JvmUtil.toJvmAddress(valueAddress));
		
		String allocatedStr = directMemoryService.getObject(currentAddress);

		currentAddress += strSize;
		
		return allocatedStr;
	}
	
	@Override
	public synchronized void init(StringOffHeapPoolCreateParameter parameter) {
		init(parameter.getEstimatedStringCount(), parameter.getEstimatedStringLength());
	}
	
	@Override
	public synchronized void reset() {
		init();
	}
	
	@Override
	public void free() {
		directMemoryService.freeMemory(allocationStartAddress);
	}
	
	@Override
	public DeeplyForkableStringOffHeapPool fork() {
		return 
				new DefaultStringOffHeapPool(
						estimatedStringCount, 
						estimatedStringLength);
	}
	
}