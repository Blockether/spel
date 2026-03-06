## PUT http://localhost:44071/echo → 200 OK

### Request Headers
```
PUT http://localhost:44071/echo
```

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
date: Fri, 06 Mar 2026 12:23:56 GMT
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
curl 'http://localhost:44071/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
