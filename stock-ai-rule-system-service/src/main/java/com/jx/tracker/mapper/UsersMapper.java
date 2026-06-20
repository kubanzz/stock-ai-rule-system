package com.jx.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.domain.entity.Users;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户Mapper
 *
 * @author Benjamin
 * @since 2025-05-08
 */
@Mapper
public interface UsersMapper extends BaseMapper<Users> {
}
