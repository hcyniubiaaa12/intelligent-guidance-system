package com.guide.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.auth.entity.UserViolation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserViolationMapper extends BaseMapper<UserViolation> {
}
