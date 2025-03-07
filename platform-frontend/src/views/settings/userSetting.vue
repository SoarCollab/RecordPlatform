<script setup>

import Card from "@/components/Card.vue";
import {Message, Refresh, Select, User} from "@element-plus/icons-vue";
import {useStore} from "@/store";
import {computed, reactive, ref} from "vue";
import {accessHeader, get, post} from "@/net";
import {ElMessage} from "element-plus";
import axios from "axios";

const store=useStore()
const intro=ref('')
const baseFormRef=ref()
const emailFormRef=ref()
const registerTime=computed(()=>new Date(store.user.registerTime).toLocaleString())
const baseForm=reactive({
  username:'',
  gender:null,
  phone:'',
  qq:'',
  wx:'',
  intro:'',
})

const emailForm=reactive({
  email:'',
  code:''
})

const validateUsername = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请输入用户名'))
  } else if(!/^[a-zA-Z0-9\u4e00-\u9fa5]+$/.test(value)){
    callback(new Error('用户名不能包含特殊字符，只能是中文/英文'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { validator: validateUsername, trigger: ['blur', 'change'] },
    { min: 2, max: 8, message: '用户名的长度必须在2-8个字符之间', trigger: ['blur', 'change'] },
  ],
  email: [
    { required: true, message: '请输入邮件地址', trigger: 'blur' },
    {type: 'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change']}
  ],
    code: [
      { required: true, message: '请输入获取的验证码', trigger: 'blur' },
    ]
}

const loading=reactive({
  form:true,
  base:false,
  email:false
})

function saveDetails(){
  baseFormRef.value.validate(isValid=>{
    if (isValid){
      loading.base=true
      post('/api/user/save-details',baseForm,()=>{
        loading.base=false
        ElMessage.success('用户信息保存成功！')
        store.user.username=baseForm.username
        intro.value=baseForm.intro
      },(message)=>{
        ElMessage.warning(message)
        loading.base=false
      })
    }
  })
}
get('api/user/details',data=>{
  baseForm.username=store.user.username
  baseForm.gender=data.gender
  baseForm.phone=data.phone
  baseForm.qq=data.qq
  baseForm.wx=data.wx
  baseForm.intro=intro.value=data.intro
  emailForm.email=store.user.email
  loading.form=false
})
const coldTime=ref(0)
const isEmailValid=ref(true)
const onValidate = (prop,isValid)=>{
  if (prop==='email')
    isEmailValid.value=isValid
}
function sendEmailCode(){
  emailFormRef.value.validate(isValid =>{
    coldTime.value=60
    get(`/api/auth/ask-code?email=${emailForm.email}&type=modify`,()=>{
      ElMessage.success(`验证码已成功发送到邮箱：${emailForm.email},请注意查收！`)
      const handle=setInterval(()=>{
        coldTime.value--
        if(coldTime.value===0)
          clearInterval(handle)
      },1000)
    },(success)=>{
      ElMessage.success(success)
      coldTime.value=0
    })
  })
}
function modifyEmail() {
  emailFormRef.value.validate(isValid =>{
    if (isValid){
      loading.email=true
      post('api/user/modify-email',emailForm,()=>{
        loading.email=false
        ElMessage.success("邮箱地址修改成功！")
        store.user.email=emailForm.email
        emailForm.code=''
      },(data)=>{
        loading.email=false
        ElMessage.warning(data)
      })
    }else {
      ElMessage.warning('请将邮件地址及验证码填写完整！')
    }
  })
}
function beforeAvatarUpload(rawFile) {
  if(rawFile.type!=="image/jpeg"&&rawFile.type!=="image/png"){
    ElMessage.error("头像格式只能为JPG/PNG")
    return false
  }else if(rawFile.size/1024>100){
    ElMessage.error("头像不能大于 100KB")
    return false
  }
  return true
}
function uploadSuccess(response) {
  ElMessage.success("头像上传成功！")
  store.user.avatar=response.data
}
</script>

<template>
  <div style="display: flex;max-width: 950px;margin: auto">
    <div class="setting-left">
      <card :icon="User" title="个人信息设置" desc="在这里编编辑你的个人用户信息，你可以在隐私设置中选择是否展示这些信息"
        v-loading="loading.form">
        <el-form :model="baseForm" ref="baseFormRef" :rules="rules" label-position="top" style="margin: 0 10px 10px 10px">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="baseForm.username" maxlength="10"/>
          </el-form-item>
          <el-form-item label="性别">
            <el-radio-group  v-model="baseForm.gender">
              <el-radio :label="0">男</el-radio>
              <el-radio :label="1">女</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input  v-model="baseForm.phone" maxlength="11"/>
          </el-form-item>
          <el-form-item label="QQ号" prop="qq">
            <el-input  v-model="baseForm.qq" maxlength="13"/>
          </el-form-item>
          <el-form-item label="微信号" prop="wx">
            <el-input  v-model="baseForm.wx" maxlength="20"/>
          </el-form-item>
          <el-form-item label="个人简介" prop="intro">
            <el-input v-model="baseForm.intro" maxlength="200" type="textarea" :rows="6"/>
          </el-form-item>
          <div>
            <el-button :icon="Select"  @click="saveDetails" :loading="loading.base" type="success">
              保存用户信息
            </el-button>
          </div>
        </el-form>
      </card>
      <card style="margin-top: 10px" :icon="Message" title="电子邮件设置" desc="在这里更换帐号绑定的默认电子邮箱">
        <el-form :model="emailForm" @validate="onValidate" ref="emailFormRef" :rules="rules" label-position="top" style="margin: 0 10px 10px 10px">
          <el-form-item label="电子邮箱地址" prop="email">
            <el-input  v-model="emailForm.email"/>
          </el-form-item>
          <el-form-item prop="code">
            <el-row style="width: 100%" :gutter="10">
              <el-col :span="18">
                <el-input v-model="emailForm.code" placeholder="请获取验证码"/>
              </el-col>
              <el-col :span="6">
                <el-button @click="sendEmailCode" type="success" :disabled="!isEmailValid||coldTime>0" style="width: 100%" plain>
                  {{ coldTime>0 ? `请稍等 ${coldTime} 秒`:`获取验证码` }}
                </el-button>
              </el-col>
            </el-row>
          </el-form-item>
          <div>
            <el-button type="success" @click="modifyEmail" :loading="loading.email" :icon="Refresh">
              更新电子邮件
            </el-button>
          </div>
        </el-form>
      </card>
    </div>
    <div class="setting-right">
      <div style="position: sticky;top: 20px">
        <card>
          <div style="text-align: center;padding: 5px 15px 0 15px">
            <el-avatar :size="70" :src="store.avatarUrl"/>
            <div style="margin:5px 0">
              <el-Upload
                :action="axios.defaults.baseURL+'/api/file/upload/avatar'"
                :show-file-list="false"
                :before-upload="beforeAvatarUpload"
                :on-success="uploadSuccess"
                :headers="accessHeader()"
              >
                <el-button size="small" round>上传头像</el-button>
              </el-Upload>
            </div>
            <div style="font-weight: bold">你好，{{store.user.username}}</div>
          </div>
          <el-divider style="margin: 10px 0"/>
          <div style="font-size: 14px;color: gray;padding: 10px">
            {{ intro ||'这个用户很神秘，没有个人简介！' }}
          </div>
        </card>
        <card style="margin-top: 10px;font-size: 14px">
          <div>帐号注册时间：{{registerTime}}</div>
          <div style="color: gray">欢迎加入本论坛！</div>
        </card>
      </div>
    </div>
  </div>

</template>

<style scoped>
.setting-left{
  flex: 1;
  margin: 20px;
}
.setting-right{
  width: 300px;
  margin: 20px 30px 20px 0;
}
</style>