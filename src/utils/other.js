export function fitByUnit(value,unit) {
  const units=['B','KB','MB','GB','TB','PB']
  let index=units.indexOf(unit)
  while ((value<1 && value!==0 && index>0)||(value>=1024 && index <units.length)) {
    if (value>=1024){
      value/=1024
      index++
    }else {
      value*=1024
      index--
    }
  }
  return `${value.toFixed(1)} ${units[index]}`
}
export function percentageToStatus(percentage) {
  if (percentage<50)
    return 'success'
  else if (percentage<80)
    return 'warning'
  else
    return 'exception'
}
export function cpuNameToImage(name) {
  if (name.indexOf('Apple')>=0)
    return 'Apple.png'
  else if (name.indexOf('AMD')>=0)
    return 'AMD.png'
  else
    return 'Intel.png'
}
export function osNameToIcon(name) {
  if(name.indexOf('Ubuntu') >= 0)
    return {icon: 'fa-ubuntu', color: '#db4c1a'}
  else if(name.indexOf('CentOS') >= 0)
    return {icon: 'fa-centos', color: '#9dcd30'}
  else if(name.indexOf('macOS') >= 0)
    return {icon: 'fa-apple', color: 'grey'}
  else if(name.indexOf('Windows') >= 0)
    return {icon: 'fa-windows', color: '#3578b9'}
  else if(name.indexOf('Debian') >= 0)
    return {icon: 'fa-debian', color: '#a80836'}
  else
    return {icon: 'fa-linux', color: 'grey'}
}