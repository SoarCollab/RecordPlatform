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
export const saveShareFileApi = (sharingFileIdList) => {
  return request({
    url: '/file/saveShareFile',
    method: 'post',
    data: {
      sharingFileIdList
    }
  });
};

// /file/share 生成分享码
export const generateShareCodeApi = (fileHash, maxAccesses) => {
  return request({
    url: '/file/share',
    method: 'post',
    data: {
      fileHash,
      maxAccesses
    },
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    }
  });
};

// file/getSharingFiles 获取分享文件列表
export const getSharingFilesApi = (sharingCode) => {
  return request({
    url: '/file/getSharingFiles',
    method: 'get',
    params: {
      sharingCode
    },
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    }
  });
};


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

// /api/file/getTransaction 根据交易hash获取对应的文件交易记录
export const getFileTransactionApi = (transactionHash) => {
  return request({
    url: '/file/getTransaction',
    method: 'get',
    params: {
      transactionHash
    }
  });
};

