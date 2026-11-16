package com.guide.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.memory.entity.MemoryLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryLogMapper extends BaseMapper<MemoryLog> {
}
