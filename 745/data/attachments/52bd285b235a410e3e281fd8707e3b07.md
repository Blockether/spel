## POST http://localhost:33357/echo → 200 OK

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
date: Fri, 11 Sep 2026 11:43:51 GMT
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
curl 'http://localhost:33357/echo' \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
  "action": "test"
}'
```
