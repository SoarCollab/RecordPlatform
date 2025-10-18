# Monitor System User Guide

This guide provides comprehensive instructions for using the Monitor System web dashboard, including client management, metrics monitoring, and system administration.

## Table of Contents

1. [Getting Started](#getting-started)
2. [Dashboard Overview](#dashboard-overview)
3. [Client Management](#client-management)
4. [Metrics Monitoring](#metrics-monitoring)
5. [Real-time Monitoring](#real-time-monitoring)
6. [SSH Terminal](#ssh-terminal)
7. [Data Export](#data-export)
8. [User Management](#user-management)
9. [Alerts and Notifications](#alerts-and-notifications)
10. [Settings and Configuration](#settings-and-configuration)

## Getting Started

### 1. Accessing the Dashboard

Open your web browser and navigate to the Monitor System dashboard:
- Production: `https://monitor.yourdomain.com`
- Development: `http://localhost:3000`

### 2. Login

1. Enter your username and password
2. Click "Login" button
3. If you forgot your password, click "Forgot Password?" to reset it

### 3. First Time Setup

After logging in for the first time:
1. Review the dashboard overview
2. Check system status indicators
3. Familiarize yourself with the navigation menu

## Dashboard Overview

### Main Dashboard Components

#### 1. Header Navigation
- **Logo**: Monitor System branding
- **Search**: Global search for clients and metrics
- **Notifications**: Alert notifications and system messages
- **User Menu**: Profile settings, logout, and help

#### 2. Sidebar Navigation
- **Dashboard**: Main overview page
- **Clients**: Client management and monitoring
- **Metrics**: Historical metrics and analytics
- **Alerts**: Alert management and configuration
- **SSH**: Remote terminal access
- **Reports**: Data export and reporting
- **Settings**: System configuration
- **Users**: User and permission management (admin only)

#### 3. Main Content Area
- **Widgets**: Customizable monitoring widgets
- **Charts**: Real-time and historical charts
- **Tables**: Client lists and data tables
- **Forms**: Configuration and management forms

#### 4. Status Bar
- **Connection Status**: WebSocket connection indicator
- **System Health**: Overall system health status
- **Active Alerts**: Count of active alerts

### Dashboard Widgets

#### System Overview Widget
- Total clients count
- Online/offline status
- System health indicators
- Recent alerts summary

#### Real-time Metrics Widget
- Live CPU, memory, and disk usage
- Network traffic indicators
- Top resource consumers
- Performance trends

#### Client Status Widget
- Client online/offline status
- Last heartbeat timestamps
- Connection quality indicators
- Geographic distribution (if configured)

## Client Management

### 1. Viewing Clients

#### Client List View
1. Navigate to **Clients** in the sidebar
2. View all registered clients in a table format
3. Use filters to find specific clients:
   - **Status**: Online, Offline, All
   - **Location**: Filter by geographic location
   - **OS Type**: Filter by operating system
   - **Search**: Search by name or IP address

#### Client Details View
1. Click on a client name in the list
2. View detailed client information:
   - System specifications (CPU, memory, disk)
   - Operating system details
   - Network configuration
   - Registration information
   - Current status and last heartbeat

### 2. Adding New Clients

#### Generate Registration Token (Admin Only)
1. Navigate to **Clients** → **Add Client**
2. Click "Generate Registration Token"
3. Copy the generated token
4. Provide the token to the client administrator

#### Client Registration Process
1. Install the monitor client software on the target system
2. Configure the client with the registration token
3. Start the client service
4. Verify the client appears in the dashboard

### 3. Managing Clients

#### Rename Client
1. Go to client details page
2. Click "Edit" button
3. Update client name and location
4. Click "Save Changes"

#### Delete Client
1. Go to client details page
2. Click "Delete Client" button
3. Confirm deletion in the popup dialog
4. Client will be removed from monitoring

#### Configure SSH Access
1. Go to client details page
2. Click "SSH Settings" tab
3. Enter SSH connection details:
   - IP address
   - Port (default: 22)
   - Username
   - Password or SSH key
4. Click "Save SSH Configuration"

## Metrics Monitoring

### 1. Real-time Metrics

#### Live Dashboard
1. Navigate to **Dashboard**
2. View real-time metrics widgets
3. Metrics update automatically every 30 seconds
4. Click on widgets to view detailed charts

#### Individual Client Monitoring
1. Go to **Clients** and select a client
2. View real-time metrics for the selected client:
   - CPU usage percentage
   - Memory usage (used/total)
   - Disk usage (used/total/free)
   - Network traffic (upload/download)
   - Process information

### 2. Historical Metrics

#### Time Series Charts
1. Navigate to **Metrics** in the sidebar
2. Select client(s) to monitor
3. Choose time range:
   - Last hour
   - Last 24 hours
   - Last 7 days
   - Last 30 days
   - Custom range
4. Select metrics to display:
   - CPU usage
   - Memory usage
   - Disk usage
   - Network traffic

#### Chart Interactions
- **Zoom**: Click and drag to zoom into specific time periods
- **Pan**: Hold Shift and drag to pan across time
- **Legend**: Click legend items to show/hide metrics
- **Tooltip**: Hover over data points for detailed values

### 3. Performance Analytics

#### Trend Analysis
1. Go to **Metrics** → **Analytics**
2. View performance trends over time
3. Identify patterns and anomalies
4. Compare metrics across multiple clients

#### Resource Utilization Reports
1. Navigate to **Reports** → **Resource Utilization**
2. Generate reports for specific time periods
3. View aggregated statistics:
   - Average, minimum, maximum values
   - Peak usage times
   - Resource efficiency metrics

## Real-time Monitoring

### 1. WebSocket Connection

The dashboard uses WebSocket connections for real-time updates:
- **Connection Status**: Indicated in the status bar
- **Auto-reconnect**: Automatically reconnects if connection is lost
- **Real-time Updates**: Metrics update without page refresh

### 2. Live Alerts

#### Alert Notifications
- Alerts appear as notifications in the header
- Click notifications to view alert details
- Alerts are color-coded by severity:
  - **Red**: Critical alerts
  - **Orange**: Warning alerts
  - **Blue**: Information alerts

#### Alert Management
1. Navigate to **Alerts**
2. View active alerts list
3. Acknowledge alerts to clear notifications
4. View alert history and trends

### 3. System Health Monitoring

#### Health Indicators
- **Green**: All systems operational
- **Yellow**: Minor issues detected
- **Red**: Critical issues requiring attention

#### Health Details
1. Click on health indicator in status bar
2. View detailed system health information:
   - Service status
   - Database connectivity
   - Cache performance
   - Network connectivity

## SSH Terminal

### 1. Accessing SSH Terminal

#### Prerequisites
- SSH configuration must be set up for the client
- User must have SSH access permissions
- Client must be online and accessible

#### Opening Terminal
1. Navigate to **Clients** and select a client
2. Click "SSH Terminal" button
3. Terminal window opens in a new tab or modal
4. Authentication is handled automatically using saved credentials

### 2. Using the Terminal

#### Terminal Features
- **Full terminal emulation**: Supports colors, cursor movement, etc.
- **Copy/Paste**: Right-click for context menu
- **Resize**: Drag terminal borders to resize
- **Multiple sessions**: Open multiple terminals simultaneously

#### Terminal Commands
- All standard Linux/Unix commands are supported
- File editing with vi, nano, etc.
- System monitoring with top, htop, ps, etc.
- Log viewing with tail, less, grep, etc.

#### Session Management
- **New Session**: Click "New Terminal" to open additional sessions
- **Close Session**: Type `exit` or click the close button
- **Session Timeout**: Sessions timeout after 30 minutes of inactivity

### 3. Terminal Security

#### Security Features
- All connections are encrypted using TLS
- Authentication uses saved SSH credentials
- Session logging for audit purposes
- Automatic session cleanup

#### Best Practices
- Use strong SSH passwords or key-based authentication
- Limit SSH access to necessary users only
- Monitor SSH session logs regularly
- Close terminal sessions when not in use

## Data Export

### 1. Export Options

#### Supported Formats
- **CSV**: Comma-separated values for spreadsheet applications
- **JSON**: JavaScript Object Notation for programmatic use
- **Excel**: Microsoft Excel format with formatting

#### Export Types
- **Synchronous**: Immediate export for small datasets
- **Asynchronous**: Background export for large datasets
- **Scheduled**: Automated exports at regular intervals

### 2. Creating Exports

#### Manual Export
1. Navigate to **Reports** → **Data Export**
2. Configure export parameters:
   - **Clients**: Select one or more clients
   - **Time Range**: Choose start and end dates
   - **Metrics**: Select metrics to include
   - **Format**: Choose export format
   - **Aggregation**: Raw data, hourly, or daily aggregation
3. Click "Export Data"
4. Download file when export completes

#### Scheduled Exports
1. Go to **Reports** → **Scheduled Exports**
2. Click "Create Schedule"
3. Configure schedule parameters:
   - **Name**: Export schedule name
   - **Frequency**: Daily, weekly, monthly
   - **Time**: Execution time
   - **Recipients**: Email addresses for delivery
   - **Export Settings**: Same as manual export
4. Click "Save Schedule"

### 3. Managing Exports

#### Export History
1. Navigate to **Reports** → **Export History**
2. View list of all exports:
   - Export ID and name
   - Status (pending, processing, completed, failed)
   - Creation and completion times
   - File size and record count
3. Download completed exports
4. Cancel pending exports if needed

#### Export Status Monitoring
- **Real-time Status**: Export progress updates in real-time
- **Notifications**: Email notifications when exports complete
- **Error Handling**: Detailed error messages for failed exports

## User Management

### 1. User Accounts (Admin Only)

#### Creating Users
1. Navigate to **Users** → **User Management**
2. Click "Add User"
3. Fill in user details:
   - Username
   - Email address
   - Password
   - Role (Admin, User, Viewer)
4. Click "Create User"

#### Managing Users
- **Edit User**: Update user information and permissions
- **Reset Password**: Generate new password for user
- **Disable User**: Temporarily disable user access
- **Delete User**: Permanently remove user account

### 2. Sub-Accounts

#### Creating Sub-Accounts
1. Go to **Users** → **Sub-Accounts**
2. Click "Create Sub-Account"
3. Configure sub-account:
   - Basic information (username, email, password)
   - Client access permissions
   - Feature permissions
4. Click "Create Sub-Account"

#### Sub-Account Permissions
- **Client Access**: Limit access to specific clients
- **Feature Access**: Control access to dashboard features
- **Read-Only**: Restrict to view-only access
- **Time-based**: Set account expiration dates

### 3. Profile Management

#### User Profile
1. Click user menu in header
2. Select "Profile Settings"
3. Update profile information:
   - Display name
   - Email address
   - Time zone
   - Language preferences
4. Click "Save Changes"

#### Password Change
1. Go to Profile Settings
2. Click "Change Password"
3. Enter current password
4. Enter new password (minimum 8 characters)
5. Confirm new password
6. Click "Update Password"

## Alerts and Notifications

### 1. Alert Configuration

#### Creating Alert Rules
1. Navigate to **Alerts** → **Alert Rules**
2. Click "Create Alert Rule"
3. Configure alert parameters:
   - **Name**: Alert rule name
   - **Condition**: Metric threshold condition
   - **Severity**: Critical, Warning, or Info
   - **Clients**: Apply to specific clients or all
   - **Notification**: Email, SMS, or webhook
4. Click "Save Alert Rule"

#### Alert Conditions
- **Threshold**: Metric exceeds specified value
- **Trend**: Metric shows increasing/decreasing trend
- **Availability**: Client goes offline
- **Custom**: Complex conditions using expressions

### 2. Notification Settings

#### Email Notifications
1. Go to **Settings** → **Notifications**
2. Configure email settings:
   - SMTP server configuration
   - Default sender address
   - Email templates
3. Test email delivery
4. Save configuration

#### Webhook Notifications
1. Configure webhook endpoints
2. Set authentication if required
3. Test webhook delivery
4. Monitor webhook logs

### 3. Alert Management

#### Active Alerts
1. Navigate to **Alerts** → **Active Alerts**
2. View current active alerts
3. Acknowledge alerts to clear notifications
4. Add comments to alerts for tracking

#### Alert History
1. Go to **Alerts** → **Alert History**
2. View historical alert data
3. Filter by time range, severity, or client
4. Export alert data for analysis

## Settings and Configuration

### 1. System Settings (Admin Only)

#### General Settings
1. Navigate to **Settings** → **General**
2. Configure system-wide settings:
   - System name and description
   - Default time zone
   - Session timeout
   - Data retention policies
3. Click "Save Settings"

#### Database Settings
1. Go to **Settings** → **Database**
2. Configure database connections:
   - MySQL connection settings
   - InfluxDB configuration
   - Redis cache settings
3. Test connections
4. Save configuration

### 2. Dashboard Customization

#### Widget Configuration
1. Go to **Dashboard**
2. Click "Customize Dashboard"
3. Add, remove, or rearrange widgets
4. Configure widget settings:
   - Refresh intervals
   - Display options
   - Color schemes
5. Save dashboard layout

#### Theme Settings
1. Navigate to **Settings** → **Appearance**
2. Choose theme:
   - Light theme
   - Dark theme
   - Auto (based on system preference)
3. Customize colors and fonts
4. Save appearance settings

### 3. Integration Settings

#### API Configuration
1. Go to **Settings** → **API**
2. Generate API keys for external integrations
3. Configure rate limiting
4. Set up webhook endpoints
5. Monitor API usage

#### External Services
1. Navigate to **Settings** → **Integrations**
2. Configure external service connections:
   - Slack notifications
   - PagerDuty integration
   - Grafana dashboards
   - Prometheus metrics export
3. Test integrations
4. Save configuration

## Troubleshooting

### Common Issues

#### Dashboard Not Loading
1. Check internet connection
2. Verify server status
3. Clear browser cache and cookies
4. Try different browser or incognito mode

#### Real-time Updates Not Working
1. Check WebSocket connection status
2. Verify firewall settings
3. Refresh the page
4. Check browser console for errors

#### SSH Terminal Not Connecting
1. Verify SSH configuration
2. Check client connectivity
3. Validate SSH credentials
4. Review firewall rules

#### Data Export Failures
1. Check export parameters
2. Verify sufficient disk space
3. Review export logs
4. Contact administrator if issues persist

### Getting Help

#### Support Resources
- **Documentation**: Comprehensive guides and API documentation
- **FAQ**: Frequently asked questions and solutions
- **Community Forum**: User community discussions
- **Support Tickets**: Direct support for technical issues

#### Contact Information
- **Email**: support@monitor.com
- **Phone**: +1-555-MONITOR
- **Live Chat**: Available during business hours
- **Emergency**: 24/7 emergency support for critical issues

---

For additional information, see:
- [API Documentation](API.md)
- [Deployment Guide](DEPLOYMENT.md)
- [Certificate Management Guide](CERTIFICATE_MANAGEMENT.md)