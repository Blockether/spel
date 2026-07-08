## PUT http://localhost:41811/echo → 200 OK

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
date: Wed, 08 Jul 2026 16:44:35 GMT
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
curl 'http://localhost:41811/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
