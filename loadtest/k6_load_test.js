import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

// Load user IDs from a file. The file should contain one user ID per line.
const userIDs = new SharedArray('user_ids', function () {
    return open('./user_ids.txt').split('\n').filter(Boolean); // Filter out any empty lines
});

// Read duration from environment variable, default to 30s if not provided
const duration = __ENV.DURATION || '30s';
const port = __ENV.SUT_PORT || 18080;
const testRunId = __ENV.TEST_RUN_ID || 'no-test-run-id';

export const options = {
    scenarios: {
        account_info_test: {
            executor: 'constant-arrival-rate',
            rate: 1,  // Number of iterations to start per time unit
            timeUnit: '1s',
            duration: duration,  // Total duration of the test
            preAllocatedVUs: 100,  // Pre-allocate virtual users
            maxVUs: 1000,  // Maximum number of virtual users
            exec: 'accountInfoTest', // function to execute
        },
        transactions_test: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            duration: duration,
            preAllocatedVUs: 100,
            maxVUs: 1000,
            exec: 'transactionTest',
        },
        health_check: {
            executor: 'constant-arrival-rate',
            rate: 1,
            timeUnit: '1s',
            preAllocatedVUs: 1,
            maxVUs: 10,
            duration: duration,
            exec: 'healthCheck',
        },
    },
};

export function accountInfoTest() {
    // Randomly pick a user ID from the loaded list
    let userId = userIDs[Math.floor(Math.random() * userIDs.length)];

    let params = {
        timeout: '60s',
        headers: {
            'perfana-test-run-id': `${testRunId}`,
            'perfana-request-name': 'accountInfo'
        },
        tags: {
            name: 'accountInfo',
        }
    }
    // Make the HTTP GET request
    let response = http.get(`http://localhost:${port}/accountInfo?userId=${userId}`, params);

    // Parse the response body
    let body = {};
    try {
        body = JSON.parse(response.body);
    } catch (e) {
        // If response is not valid JSON, this will be handled in the checks
    }

    // Check the response status and also check for fallback responses
    check(response, {
        'is status 200': (r) => r.status === 200,
        'duration < 400ms': (r) => r.timings.duration < 400,
        'fallback account': (r) => {
            // Check if account is a fallback account (contains "FALLBACK" in accountNumber)
            return body.account && body.account.accountNumber.includes("FALLBACK");
        },
        'fallback balance': (r) => {
            // Check if balance is available and is fallback (0, "Not Available")
            return body.balance && (body.balance.amount === 0 && body.balance.currency === "Not Available");
        }
    });
}

export function transactionTest() {
    // Randomly pick a user ID from the loaded list
    let userId = userIDs[Math.floor(Math.random() * userIDs.length)];

    let params = {
        timeout: '60s',
        headers: {
            'perfana-test-run-id': `${testRunId}`,
            'perfana-request-name': 'transactions'
        },
        tags: {
            name: 'transactions',
        }
    }
    // Make the HTTP GET request
    let response = http.get(`http://localhost:${port}/transactions?userId=${userId}`, params);

    // Check the response status
    check(response, {
        'is status 200': (r) => r.status === 200,
        'duration < 400ms': (r) => r.timings.duration < 400
    });
}

export function healthCheck() {
    let params = {
        timeout: '5s',
        headers: {
            'perfana-test-run-id': `${testRunId}`,
            'perfana-request-name': 'healthCheck'
        },
        tags: {
            name: 'healthCheck',
        }
    }
    const healthCheckRes = http.get(`http://localhost:${port}/actuator/health`, params);
    check(healthCheckRes, {
        'health check status is 200': (r) => r.status === 200,
    });
}