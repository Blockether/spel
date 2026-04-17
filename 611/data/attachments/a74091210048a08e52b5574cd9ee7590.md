## DELETE http://localhost:45605/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 17 Apr 2026 13:13:20 GMT
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
curl 'http://localhost:45605/echo' \
  -X DELETE
```
