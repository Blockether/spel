## PUT http://localhost:42141/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Tue, 04 Aug 2026 20:42:11 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:42141/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
