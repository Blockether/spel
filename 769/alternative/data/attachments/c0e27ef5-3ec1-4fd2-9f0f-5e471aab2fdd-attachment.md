## DELETE http://localhost:43453/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 02 Oct 2026 15:50:51 GMT
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
curl 'http://localhost:43453/echo' \
  -X DELETE
```
