## POST http://localhost:34463/echo → 200 OK

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
date: Fri, 11 Sep 2026 15:06:50 GMT
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
curl 'http://localhost:34463/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "action": "test"
}'
```
