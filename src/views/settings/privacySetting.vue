<script setup>

import Card from "@/components/Card.vue";
import {Setting, Switch} from "@element-plus/icons-vue";
import {reactive, ref} from "vue";
import {get, post} from "@/net";
import {ElMessage} from "element-plus";

const form =reactive({
  password:'',
  new_password:'',
  new_password_repeat:''
})
const validatePassword = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.new_password) {
    callback(new Error("两次输入的密码不一致"))
  } else {
    callback()
  }
}

const formRef=ref()
const valid=ref(false)
const onValidate = (prop,isValid)=> valid.value=isValid
function resetPassword() {
  formRef.value.validate(valid=>{
    if (valid){
      post('api/user/change-password',form,()=>{
        ElMessage.success('密码修改成功！')
        formRef.value.resetFields()
      })
    }
  })
}
const saving=ref(true)
const privacy=reactive({
  phone:false,
  wx:false,
  qq:false,
  email:false,
  gender:false
})
get('api/user/privacy',data=>{
  privacy.phone=data.phone
  privacy.email=data.email
  privacy.qq=data.qq
  privacy.wx=data.wx
  privacy.gender=data.gender
  saving.value=false
})
function savePrivacy(type, status) {
  saving.value=true
  post('api/user/save-privacy',{
    type: type,
    status: status
  },()=>{
    saving.value=false
    console.log(privacy)
    ElMessage.success('隐私设置修改成功！')
  },(message)=>{
    saving.value=false
    ElMessage.warning(message)
  })
}
const rules={
  password: [
    { required: true, message: '请输入原来的密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码的长度必须在6-20个字符之间', trigger: ['blur', 'change'] }
  ],
  new_password:[
    { required: true, message: '请输入新的密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码的长度必须在6-20个字符之间', trigger: ['blur', 'change'] }
  ],
  new_password_repeat: [
    { required: true, message: '请输入新的密码', trigger: 'blur' },
    { validator: validatePassword, trigger: ['blur', 'change'] },
  ]
}
</script>

<template>
<div style="margin: auto;max-width: 600px">
  <div style="margin-top: 20px">
    <card :icon="Setting" v-loading="saving" title="隐私设置" desc="在这里设置哪些帐号信息能够被他人查看">
      <div class="checkbox-list">
        <el-checkbox @change="savePrivacy('email',privacy.email)"
        v-model="privacy.email">公开展示我的电子邮件地址</el-checkbox>
        <el-checkbox @change="savePrivacy('phone',privacy.phone)"
                     v-model="privacy.phone">公开展示我的手机号</el-checkbox>
        <el-checkbox @change="savePrivacy('wx',privacy.wx)"
                     v-model="privacy.wx">公开展示我的微信号</el-checkbox>
        <el-checkbox @change="savePrivacy('qq',privacy.qq)"
                     v-model="privacy.qq">公开展示我的QQ号</el-checkbox>
        <el-checkbox @change="savePrivacy('gender',privacy.gender)"
                     v-model="privacy.gender">公开展示我的性别</el-checkbox>
      </div>
    </card>
    <card style="margin-top: 20px" :icon="Setting" title="修改密码" desc="在这里修改帐号的密码">
      <el-form :model="form" :rules="rules" ref="formRef" @validate="onValidate" label-width="100" style="margin: 20px">
        <el-form-item label="当前密码" prop="password">
          <el-input type="password" :prefix-icon="Lock" v-model="form.password" placeholder="请输入当前的帐号密码" maxlength="20"/>
        </el-form-item>
        <el-form-item label="新的密码" prop="new_password">
          <el-input type="password" :prefix-icon="Lock" v-model="form.new_password" placeholder="请输入新的帐号密码" maxlength="20"/>
        </el-form-item>
        <el-form-item label="重复新密码" prop="new_password_repeat">
          <el-input type="password" :prefix-icon="Lock" v-model="form.new_password_repeat" placeholder="请重复输入新的帐号密码" maxlength="20"/>
        </el-form-item>
        <div style="text-align: center">
          <el-button @click="resetPassword" :icon="Switch" type="success">
            立即更新密码
          </el-button>
        </div>

      </el-form>
    </card>
  </div>
</div>
</template>

<style scoped>
.checkbox-list{
  margin:10px 0 0 10px;
  display: flex;
  flex-direction: column;
}
</style>