
// /file/list 获取用户文件列表

import request from "@/utils/request.js";

export const getFileListApi = () => {
  return request({
    url: '/file/list',
    method: 'get',
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

// /file/delete 删除文件

