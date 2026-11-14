package com.guide.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.memory.entity.Memory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {
}
