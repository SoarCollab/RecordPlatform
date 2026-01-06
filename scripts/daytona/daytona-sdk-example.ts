import { Daytona } from '@daytonaio/sdk';
import * as dotenv from 'dotenv';

dotenv.config();

const SNAPSHOT_NAME = 'recordplatform-test-env';
const REPO_URL = 'https://github.com/SoarCollab/RecordPlatform.git';

interface TestResults {
  backendPassed: boolean;
  frontendPassed: boolean;
  duration: number;
  coverageReports: {
    backend?: string;
    frontend?: string;
  };
}

async function createSnapshot(): Promise<void> {
  console.log('🏗️  Creating RecordPlatform test snapshot...\n');

  const daytona = new Daytona({
    apiKey: process.env.DAYTONA_API_KEY,
    target: process.env.DAYTONA_TARGET || 'us',
  });

  try {
    const sandbox = await daytona.create({
      snapshot: 'docker:28.3.3-dind',
      resources: { cpu: 4, memory: 8, disk: 10 },
    });

    console.log(`✓ Sandbox created: ${sandbox.id}`);

    await sandbox.git.clone({
      url: REPO_URL,
      path: '/workspace/project',
    });

    console.log('✓ Repository cloned');

    const buildScript = `
      set -e
      cd /workspace/project
      
      echo "Installing platform-api..."
      mvn -f platform-api/pom.xml clean install -DskipTests -q
      
      echo "Caching backend dependencies..."
      mvn -f platform-backend/pom.xml dependency:go-offline -q
      mvn -f platform-backend/pom.xml clean verify -DskipTests -q
      
      echo "Caching frontend dependencies..."
      cd platform-frontend
      pnpm install --frozen-lockfile
      
      echo "✓ Snapshot preparation complete"
    `;

    const result = await sandbox.process.executeCommand(buildScript, '/workspace');
    
    if (result.exitCode !== 0) {
      throw new Error(`Snapshot preparation failed: ${result.result}`);
    }

    console.log('✓ Dependencies cached');

    await sandbox.createSnapshot(SNAPSHOT_NAME);
    
    console.log(`\n✅ Snapshot '${SNAPSHOT_NAME}' created successfully!`);
    console.log('\nNext steps:');
    console.log(`  npx tsx ${__filename} run-tests`);

    await sandbox.delete();

  } catch (error) {
    console.error('❌ Snapshot creation failed:', error);
    process.exit(1);
  }
}

async function runTests(): Promise<TestResults> {
  console.log('🧪 Running RecordPlatform tests from snapshot...\n');

  const daytona = new Daytona({
    apiKey: process.env.DAYTONA_API_KEY,
    target: process.env.DAYTONA_TARGET || 'us',
  });

  let sandbox;
  const startTime = Date.now();

  try {
    sandbox = await daytona.create({
      snapshot: SNAPSHOT_NAME,
      resources: { cpu: 4, memory: 8, disk: 10 },
      ephemeral: true,
      autoStopInterval: 30,
    });

    console.log(`✓ Sandbox created from snapshot: ${sandbox.id}\n`);

    const updateResult = await sandbox.process.executeCommand(
      'cd /workspace/project && git pull origin main',
      '/workspace'
    );

    if (updateResult.exitCode === 0) {
      console.log('✓ Repository updated\n');
    }

    console.log('Running backend tests...');
    const backendResult = await sandbox.process.executeCommand(
      `
      cd /workspace/project
      mvn -f platform-backend/pom.xml clean verify -pl backend-service,backend-web -am -Pit
      `,
      '/workspace'
    );

    const backendPassed = backendResult.exitCode === 0;
    console.log(backendPassed ? '✅ Backend tests PASSED' : '❌ Backend tests FAILED');

    console.log('\nRunning frontend tests...');
    const frontendResult = await sandbox.process.executeCommand(
      `
      cd /workspace/project/platform-frontend
      pnpm install --frozen-lockfile
      pnpm test:coverage
      `,
      '/workspace'
    );

    const frontendPassed = frontendResult.exitCode === 0;
    console.log(frontendPassed ? '✅ Frontend tests PASSED' : '❌ Frontend tests FAILED');

    const backendCoverage = await sandbox.fs.downloadFile(
      '/workspace/project/platform-backend/backend-web/target/site/jacoco/jacoco.xml'
    ).catch(() => null);

    const frontendCoverage = await sandbox.fs.downloadFile(
      '/workspace/project/platform-frontend/coverage/lcov.info'
    ).catch(() => null);

    const duration = Date.now() - startTime;

    const results: TestResults = {
      backendPassed,
      frontendPassed,
      duration,
      coverageReports: {
        backend: backendCoverage ? 'Downloaded' : undefined,
        frontend: frontendCoverage ? 'Downloaded' : undefined,
      },
    };

    console.log('\n' + '='.repeat(50));
    console.log('Test Summary:');
    console.log('='.repeat(50));
    console.log(`Backend:  ${backendPassed ? '✅ PASSED' : '❌ FAILED'}`);
    console.log(`Frontend: ${frontendPassed ? '✅ PASSED' : '❌ FAILED'}`);
    console.log(`Duration: ${Math.round(duration / 1000)}s`);
    console.log('='.repeat(50));

    if (backendCoverage) {
      console.log('\n📊 Backend coverage report downloaded');
    }
    if (frontendCoverage) {
      console.log('📊 Frontend coverage report downloaded');
    }

    return results;

  } catch (error) {
    console.error('❌ Test execution failed:', error);
    throw error;
  } finally {
    if (sandbox) {
      console.log('\n🧹 Cleaning up sandbox...');
      await sandbox.delete();
    }
  }
}

async function main() {
  const command = process.argv[2];

  if (!process.env.DAYTONA_API_KEY) {
    console.error('❌ DAYTONA_API_KEY environment variable is required');
    console.log('\nGet your API key from: https://app.daytona.io/dashboard/api-keys');
    console.log('Then: export DAYTONA_API_KEY="your-key-here"');
    process.exit(1);
  }

  switch (command) {
    case 'create-snapshot':
      await createSnapshot();
      break;

    case 'run-tests':
      const results = await runTests();
      process.exit(results.backendPassed && results.frontendPassed ? 0 : 1);

    default:
      console.log('RecordPlatform Daytona Test Runner (TypeScript SDK)\n');
      console.log('Usage:');
      console.log(`  npx tsx ${__filename} create-snapshot  - Create optimized snapshot`);
      console.log(`  npx tsx ${__filename} run-tests        - Run tests from snapshot`);
      console.log('\nPrerequisites:');
      console.log('  export DAYTONA_API_KEY="your-key"');
      console.log('  npm install @daytonaio/sdk dotenv tsx');
      process.exit(1);
  }
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
