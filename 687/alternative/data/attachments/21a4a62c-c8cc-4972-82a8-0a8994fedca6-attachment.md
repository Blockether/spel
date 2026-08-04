## POST http://localhost:41729/echo → 200 OK

### Request Headers
```
Content-Type: application/json
```

### Request Body
```json
{
  "action": "test"
}
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Tue, 04 Aug 2026 22:17:11 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "action": "test"
  }
}
```

### cURL
```bash
curl 'http://localhost:41729/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "action": "test"
}'
```
