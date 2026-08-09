## PUT http://localhost:33477/echo → 200 OK

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
date: Sun, 09 Aug 2026 19:26:14 GMT
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
curl 'http://localhost:33477/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
