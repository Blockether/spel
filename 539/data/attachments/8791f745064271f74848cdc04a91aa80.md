## DELETE http://localhost:33315/echo → 200 OK

### Request Headers
```
DELETE http://localhost:33315/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 09 Apr 2026 18:15:19 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:33315/echo' \
  -X DELETE
```
