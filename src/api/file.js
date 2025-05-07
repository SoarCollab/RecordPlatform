
// /file/list 获取用户文件列表

import request from "@/utils/request.js";

export const getFileListApi = (params) => {
  return request({
    url: '/file/page',
    method: 'get',
    params,
  });
};

// /file/saveShareFile 保存文件分享
export const saveShareFileApi = (data) => {
  return request({
    url: '/file/saveShareFile',
    method: 'post',
    data,
  });
};

// /file/share 生成分享码


// /file/address 获取文件下载地址
export const getFileAddressApi = (fileHash) => {
  return request({
    url: '/file/address',
    method: 'get',
    params: {
      fileHash
    }
  });
};

// /file/deleteByHash 删除文件
export const deleteFileApi = (hashList) => {
  return request({
    url: '/file/deleteByHash',
    method: 'delete',
    data: { hashList },
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    }
  });
};

// /file/deleteById
export const adminDeleteFileApi = (idList) => {
  return request({
    url: '/file/deleteById',
    method: 'delete',
    data: { idList },
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    }
  });
}

